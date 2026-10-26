package pe.autoperu.repuestos;

/**
 * Repuesto automotriz del inventario. Precio en soles por unidad, stock en unidades.
 */
public record Repuesto(Long id, String nombre, String categoria, double precio, int stock) {

    public Repuesto conStock(int nuevoStock) {
        return new Repuesto(id, nombre, categoria, precio, nuevoStock);
    }
}
