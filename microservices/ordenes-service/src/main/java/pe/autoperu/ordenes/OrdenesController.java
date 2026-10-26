package pe.autoperu.ordenes;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

@RestController
public class OrdenesController {

    public record NuevoOrden(Long repuestoId, int cantidad) {}

    private final OrdenesService service;

    public OrdenesController(OrdenesService service) {
        this.service = service;
    }

    /** Health check propio: no consulta al servicio de repuestos, responde siempre rapido. */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "ordenes-service");
    }

    @PostMapping("/api/ordenes")
    public ResponseEntity<Orden> crear(@RequestBody NuevoOrden body) {
        Orden orden = service.crear(body.repuestoId(), body.cantidad());
        return ResponseEntity.status(HttpStatus.CREATED).body(orden);
    }

    @GetMapping("/api/ordenes")
    public List<Orden> listar() {
        return service.listar();
    }

    @GetMapping("/api/ordenes/resumen")
    public Map<String, Object> resumen() {
        return service.resumen();
    }

    @GetMapping("/api/ordenes/{id}")
    public ResponseEntity<?> obtener(@PathVariable Long id) {
        return service.buscar(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Orden " + id + " no existe")));
    }

    @PutMapping("/api/ordenes/{id}/cancelar")
    public ResponseEntity<?> cancelar(@PathVariable Long id) {
        return service.cancelar(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Orden " + id + " no existe")));
    }

    /** Traduce las excepciones de negocio a respuestas HTTP controladas. */
    @RestControllerAdvice
    public static class GlobalExceptionHandler {

        @ExceptionHandler(OrdenesService.RepuestosNoDisponibleException.class)
        public ResponseEntity<Map<String, String>> repuestosNoDisponible(OrdenesService.RepuestosNoDisponibleException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", e.getMessage(), "detalle", "Intente nuevamente en unos segundos"));
        }

        @ExceptionHandler(OrdenesService.StockInsuficienteException.class)
        public ResponseEntity<Map<String, String>> sinStock(OrdenesService.StockInsuficienteException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }

        @ExceptionHandler(OrdenesService.RepuestoNoEncontradoException.class)
        public ResponseEntity<Map<String, String>> noEncontrado(OrdenesService.RepuestoNoEncontradoException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }

        @ExceptionHandler(IllegalArgumentException.class)
        public ResponseEntity<Map<String, String>> invalido(IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
