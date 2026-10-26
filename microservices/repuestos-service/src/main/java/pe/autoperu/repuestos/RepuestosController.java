package pe.autoperu.repuestos;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class RepuestosController {

    public record NuevoRepuesto(String nombre, String categoria, double precio, int stock) {}

    public record StockRequest(int stock) {}

    public record Disponibilidad(Long repuestoId, String nombre, double precio, int stockActual,
                                 int cantidadSolicitada, boolean disponible) {}

    private final RepuestosService service;

    public RepuestosController(RepuestosService service) {
        this.service = service;
    }

    /** Health check propio: responde rapido y NO depende de nada (ni de otros servicios ni del retardo artificial). */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "repuestos-service");
    }

    @GetMapping("/api/repuestos")
    public List<Repuesto> listar() {
        return service.listar();
    }

    @GetMapping("/api/repuestos/{id}")
    public ResponseEntity<?> obtener(@PathVariable Long id) {
        return service.buscar(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> noEncontrado(id));
    }

    @PostMapping("/api/repuestos")
    public ResponseEntity<Repuesto> crear(@RequestBody NuevoRepuesto body) {
        Repuesto creado = service.agregar(body.nombre(), body.categoria(), body.precio(), body.stock());
        return ResponseEntity.status(HttpStatus.CREATED).body(creado);
    }

    @PutMapping("/api/repuestos/{id}/stock")
    public ResponseEntity<?> actualizarStock(@PathVariable Long id, @RequestBody StockRequest body) {
        return service.actualizarStock(id, body.stock())
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> noEncontrado(id));
    }

    /** Lo consulta el servicio de ordenes antes de aceptar un orden. */
    @GetMapping("/api/repuestos/{id}/disponibilidad")
    public ResponseEntity<?> disponibilidad(@PathVariable Long id, @RequestParam int cantidad) {
        return service.buscar(id)
                .<ResponseEntity<?>>map(p -> ResponseEntity.ok(new Disponibilidad(
                        p.id(), p.nombre(), p.precio(), p.stock(), cantidad, p.stock() >= cantidad)))
                .orElseGet(() -> noEncontrado(id));
    }

    @PostMapping("/api/repuestos/{id}/reservar")
    public ResponseEntity<?> reservar(@PathVariable Long id, @RequestParam int cantidad) {
        try {
            return service.reservar(id, cantidad)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> noEncontrado(id));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<?> noEncontrado(Long id) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Repuesto " + id + " no existe"));
    }
}
