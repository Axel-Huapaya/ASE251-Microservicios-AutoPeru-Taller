package pe.autoperu.ordenes;

import java.time.Instant;

public record Orden(Long id, Long repuestoId, String repuesto, int cantidad, double total,
                     String estado, Instant fecha) {

    public static final String CONFIRMADO = "CONFIRMADO";
    public static final String CANCELADO = "CANCELADO";

    public Orden conEstado(String nuevoEstado) {
        return new Orden(id, repuestoId, repuesto, cantidad, total, nuevoEstado, fecha);
    }
}
