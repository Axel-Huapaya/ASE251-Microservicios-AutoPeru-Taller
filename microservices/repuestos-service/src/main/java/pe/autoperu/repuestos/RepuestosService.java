package pe.autoperu.repuestos;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Logica de negocio del servicio de repuestos. Todo vive en memoria (ConcurrentHashMap).
 *
 * Metricas Micrometer (nombre en Prometheus):
 *  - Counter repuestos_consultas_total          consultas de repuestos atendidas
 *  - Counter repuestos_reservas_total           reservas de stock realizadas
 *  - Counter repuestos_reservas_rechazadas_total reservas rechazadas por stock insuficiente
 *  - Gauge   repuestos_total          repuestos registrados
 *  - Gauge   repuestos_agotados       repuestos con stock 0
 *  - Gauge   repuestos_stock_total_unidades           stock total en unidades
 *  - Gauge   repuestos_delay_artificial_ms      retardo artificial activo (casuistica 2)
 *  - Timer   repuestos_consulta_seconds         duracion de cada consulta (incluye el retardo artificial)
 */
@Service
public class RepuestosService {

    private static final Logger log = LoggerFactory.getLogger(RepuestosService.class);

    private final Map<Long, Repuesto> repuestos = new ConcurrentHashMap<>();
    private final AtomicLong secuencia = new AtomicLong(0);
    private final AtomicLong delayMs = new AtomicLong(0);

    private final Counter consultas;
    private final Counter reservas;
    private final Counter reservasRechazadas;
    private final Timer timerConsulta;

    public RepuestosService(MeterRegistry registry, @Value("${repuestos.delay-ms:0}") long delayInicial) {
        this.delayMs.set(Math.max(0, delayInicial));

        this.consultas = Counter.builder("repuestos.consultas")
                .description("Consultas de repuestos atendidas por el servicio de repuestos")
                .register(registry);
        this.reservas = Counter.builder("repuestos.reservas")
                .description("Reservas de stock realizadas")
                .register(registry);
        this.reservasRechazadas = Counter.builder("repuestos.reservas.rechazadas")
                .description("Reservas rechazadas por stock insuficiente")
                .register(registry);
        this.timerConsulta = Timer.builder("repuestos.consulta")
                .description("Duracion de las consultas al servicio de repuestos")
                .publishPercentileHistogram()
                .register(registry);

        Gauge.builder("repuestos.total", repuestos, Map::size)
                .description("Repuestos registrados en el servicio de repuestos")
                .register(registry);
        Gauge.builder("repuestos.agotados", repuestos,
                        m -> m.values().stream().filter(p -> p.stock() == 0).count())
                .description("Repuestos con stock en cero")
                .register(registry);
        Gauge.builder("repuestos.stock.total.unidades", repuestos,
                        m -> m.values().stream().mapToInt(Repuesto::stock).sum())
                .description("Stock total del servicio de repuestos en unidades")
                .register(registry);
        Gauge.builder("repuestos.delay.artificial.ms", delayMs, AtomicLong::get)
                .description("Retardo artificial configurado en las respuestas (ms)")
                .register(registry);

        sembrarDatos();
        log.info("repuestos-service iniciado con {} repuestos y delay artificial de {} ms", repuestos.size(), delayMs.get());
    }

    private void sembrarDatos() {
        agregar("Pastillas de freno", "frenos", 85.00, 30);
        agregar("Filtro de aceite", "motor", 25.00, 80);
        agregar("Bateria 12V", "electrico", 280.00, 20);
        agregar("Bujia", "motor", 12.00, 120);
        agregar("Amortiguador delantero", "suspension", 190.00, 16);
        agregar("Aceite 10W-40 (litro)", "lubricantes", 45.00, 60);
    }

    public Repuesto agregar(String nombre, String categoria, double precio, int stock) {
        long id = secuencia.incrementAndGet();
        Repuesto p = new Repuesto(id, nombre, categoria, precio, stock);
        repuestos.put(id, p);
        return p;
    }

    public List<Repuesto> listar() {
        return medir(() -> {
            List<Repuesto> lista = new ArrayList<>(repuestos.values());
            lista.sort(Comparator.comparing(Repuesto::id));
            return lista;
        });
    }

    public Optional<Repuesto> buscar(Long id) {
        return medir(() -> Optional.ofNullable(repuestos.get(id)));
    }

    public Optional<Repuesto> actualizarStock(Long id, int nuevoStock) {
        if (!repuestos.containsKey(id)) {
            return Optional.empty();
        }
        Repuesto actualizado = repuestos.computeIfPresent(id, (k, p) -> p.conStock(Math.max(0, nuevoStock)));
        return Optional.ofNullable(actualizado);
    }

    /**
     * Descuenta stock de forma atomica. Devuelve el repuesto actualizado,
     * o vacio si no existe. Lanza IllegalStateException si no alcanza el stock.
     */
    public Optional<Repuesto> reservar(Long id, int cantidad) {
        medir(() -> {
            simularDelay();
            return null;
        });
        AtomicBoolean sinStock = new AtomicBoolean(false);
        Repuesto resultado = repuestos.computeIfPresent(id, (k, p) -> {
            if (p.stock() < cantidad) {
                sinStock.set(true);
                return p;
            }
            return p.conStock(p.stock() - cantidad);
        });
        if (resultado == null) {
            return Optional.empty();
        }
        if (sinStock.get()) {
            reservasRechazadas.increment();
            throw new IllegalStateException("Stock insuficiente para " + resultado.nombre());
        }
        reservas.increment();
        return Optional.of(resultado);
    }

    /** Ejecuta una consulta midiendo su duracion y aplicando el retardo artificial (si hay). */
    private <T> T medir(java.util.function.Supplier<T> consulta) {
        consultas.increment();
        return timerConsulta.record(() -> {
            simularDelay();
            return consulta.get();
        });
    }

    private void simularDelay() {
        long espera = delayMs.get();
        if (espera > 0) {
            try {
                Thread.sleep(espera);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
