package pe.autoperu.ordenes;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Logica de ordenes. El servicio de ordenes NO conoce el stock: consulta al servicio de repuestos (Servicio A)
 * por HTTP antes de aceptar cada orden.
 *
 * Metricas Micrometer (nombre en Prometheus):
 *  - Counter ordenes_creadas_total              ordenes confirmadas
 *  - Counter ordenes_rechazadas_stock_total     ordenes rechazadas porque no hay stock (venta perdida)
 *  - Counter ordenes_errores_repuestos_total     fallos al hablar con el servicio de repuestos (tag tipo=timeout|conexion|otro)
 *  - Gauge   ordenes_activas                    ordenes en estado CONFIRMADO
 *  - Gauge   ordenes_ingresos_total_soles         suma en soles de los ordenes confirmadas
 *  - Gauge   ordenes_repuestos_timeout_ms        timeout configurado hacia el servicio de repuestos
 *  - Timer   ordenes_repuestos_llamada_seconds   duracion de las llamadas al servicio de repuestos (exitosas o no)
 */
@Service
public class OrdenesService {

    private static final Logger log = LoggerFactory.getLogger(OrdenesService.class);

    // ---- Excepciones de negocio (las traduce GlobalExceptionHandler) ----
    public static class RepuestoNoEncontradoException extends RuntimeException {
        public RepuestoNoEncontradoException(String msg) { super(msg); }
    }

    public static class StockInsuficienteException extends RuntimeException {
        public StockInsuficienteException(String msg) { super(msg); }
    }

    public static class RepuestosNoDisponibleException extends RuntimeException {
        public RepuestosNoDisponibleException(String msg, Throwable causa) { super(msg, causa); }
    }

    /** Respuesta del servicio de repuestos a /disponibilidad */
    public record Disponibilidad(Long repuestoId, String nombre, double precio, int stockActual,
                                 int cantidadSolicitada, boolean disponible) {}

    private final WebClient repuestos;
    private final long timeoutMs;

    private final Map<Long, Orden> ordenes = new ConcurrentHashMap<>();
    private final AtomicLong secuencia = new AtomicLong(0);

    private final Counter creados;
    private final Counter rechazadosStock;
    private final Timer timerRepuestos;
    private final MeterRegistry registry;

    public OrdenesService(WebClient repuestosWebClient, MeterRegistry registry,
                          @Value("${repuestos.timeout-ms}") long timeoutMs) {
        this.repuestos = repuestosWebClient;
        this.timeoutMs = timeoutMs;
        this.registry = registry;

        this.creados = Counter.builder("ordenes.creadas")
                .description("Ordenes confirmados")
                .register(registry);
        this.rechazadosStock = Counter.builder("ordenes.rechazadas.stock")
                .description("Ordenes rechazados por falta de stock")
                .register(registry);
        // Se registra tambien con tipo=timeout para que la serie exista desde el arranque
        Counter.builder("ordenes.errores.repuestos").tag("tipo", "timeout")
                .description("Errores al consultar el servicio de repuestos").register(registry);
        this.timerRepuestos = Timer.builder("ordenes.repuestos.llamada")
                .description("Duracion de las llamadas HTTP al servicio de repuestos")
                .publishPercentileHistogram()
                .register(registry);

        Gauge.builder("ordenes.activas", ordenes,
                        m -> m.values().stream().filter(p -> Orden.CONFIRMADO.equals(p.estado())).count())
                .description("Ordenes confirmados vigentes")
                .register(registry);
        Gauge.builder("ordenes.ingresos.total.soles", ordenes,
                        m -> m.values().stream()
                                .filter(p -> Orden.CONFIRMADO.equals(p.estado()))
                                .mapToDouble(Orden::total).sum())
                .description("Ingresos acumulados en soles (ordenes confirmadas)")
                .register(registry);
        Gauge.builder("ordenes.repuestos.timeout.ms", () -> this.timeoutMs)
                .description("Timeout configurado hacia el servicio de repuestos (ms)")
                .register(registry);

        log.info("ordenes-service iniciado. Timeout hacia repuestos: {} ms", timeoutMs);
    }

    public Orden crear(Long repuestoId, int cantidad) {
        if (cantidad <= 0) {
            throw new IllegalArgumentException("La cantidad debe ser mayor a cero");
        }

        // 1) Consultar disponibilidad al servicio de repuestos (con timeout)
        Disponibilidad d = llamarRepuestos(() -> repuestos.get()
                .uri("/api/repuestos/{id}/disponibilidad?cantidad={c}", repuestoId, cantidad)
                .retrieve()
                .bodyToMono(Disponibilidad.class)
                .block(Duration.ofMillis(timeoutMs)), repuestoId);

        if (!d.disponible()) {
            rechazadosStock.increment();
            log.warn("Orden rechazada por stock: repuesto={} solicitado={} disponible={}",
                    repuestoId, cantidad, d.stockActual());
            throw new StockInsuficienteException("No hay stock suficiente de " + d.nombre()
                    + " (disponible: " + d.stockActual() + " unidades)");
        }

        // 2) Reservar el stock en el servicio de repuestos
        llamarRepuestos(() -> repuestos.post()
                .uri("/api/repuestos/{id}/reservar?cantidad={c}", repuestoId, cantidad)
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofMillis(timeoutMs)), repuestoId);

        long id = secuencia.incrementAndGet();
        Orden orden = new Orden(id, repuestoId, d.nombre(), cantidad,
                Math.round(d.precio() * cantidad * 100.0) / 100.0, Orden.CONFIRMADO, Instant.now());
        ordenes.put(id, orden);
        creados.increment();
        return orden;
    }

    /**
     * Ejecuta una llamada al servicio de repuestos midiendo su duracion y traduciendo los fallos:
     * 404 -> RepuestoNoEncontrado; 409 -> StockInsuficiente; timeout/conexion/5xx -> RepuestosNoDisponible.
     */
    private <T> T llamarRepuestos(java.util.function.Supplier<T> llamada, Long repuestoId) {
        Timer.Sample muestra = Timer.start(registry);
        try {
            return llamada.get();
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new RepuestoNoEncontradoException("El repuesto " + repuestoId + " no existe en el servicio de repuestos");
            }
            if (e.getStatusCode().value() == 409) {
                rechazadosStock.increment();
                throw new StockInsuficienteException("El servicio de repuestos rechazo la reserva: stock insuficiente");
            }
            throw errorRepuestos("http_" + e.getStatusCode().value(), e);
        } catch (RuntimeException e) {
            // IllegalStateException de Reactor ("Timeout on blocking read") o WebClientRequestException
            String tipo = esTimeout(e) ? "timeout" : (esConexion(e) ? "conexion" : "otro");
            throw errorRepuestos(tipo, e);
        } finally {
            muestra.stop(timerRepuestos);
        }
    }

    private RepuestosNoDisponibleException errorRepuestos(String tipo, Throwable causa) {
        Counter.builder("ordenes.errores.repuestos").tag("tipo", tipo)
                .description("Errores al consultar el servicio de repuestos").register(registry).increment();
        log.error("Fallo al consultar el servicio de repuestos (tipo={}, timeout configurado={} ms): {}",
                tipo, timeoutMs, causa.toString());
        return new RepuestosNoDisponibleException("El servicio de repuestos no respondio a tiempo o no esta disponible", causa);
    }

    private boolean esTimeout(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String n = c.getClass().getSimpleName();
            String m = String.valueOf(c.getMessage());
            if (n.contains("Timeout") || m.contains("Timeout") || m.contains("timeout")) {
                return true;
            }
        }
        return false;
    }

    private boolean esConexion(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String n = c.getClass().getSimpleName();
            if (n.contains("Connect") || n.contains("UnknownHost")) {
                return true;
            }
        }
        return false;
    }

    public List<Orden> listar() {
        List<Orden> lista = new ArrayList<>(ordenes.values());
        lista.sort(Comparator.comparing(Orden::id));
        return lista;
    }

    public Optional<Orden> buscar(Long id) {
        return Optional.ofNullable(ordenes.get(id));
    }

    public Optional<Orden> cancelar(Long id) {
        return Optional.ofNullable(ordenes.computeIfPresent(id, (k, p) -> p.conEstado(Orden.CANCELADO)));
    }

    public Map<String, Object> resumen() {
        List<Orden> confirmados = ordenes.values().stream()
                .filter(p -> Orden.CONFIRMADO.equals(p.estado())).toList();
        double ventas = confirmados.stream().mapToDouble(Orden::total).sum();
        return Map.of(
                "totalOrdenes", ordenes.size(),
                "ordenesConfirmados", confirmados.size(),
                "ventasSoles", Math.round(ventas * 100.0) / 100.0);
    }
}
