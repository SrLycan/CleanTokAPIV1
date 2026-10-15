package ec.cleantok.ordenes

import ec.cleantok.catalogo.dominio.Abastece
import ec.cleantok.catalogo.dominio.Categoria
import ec.cleantok.catalogo.dominio.Unidad
import ec.cleantok.ordenes.extraccion.Confianza
import ec.cleantok.ordenes.extraccion.DocumentoPdf
import ec.cleantok.ordenes.extraccion.Enmascarador
import ec.cleantok.ordenes.extraccion.ExtractorSercop
import ec.cleantok.ordenes.extraccion.LectorPdf
import ec.cleantok.ordenes.extraccion.Lexico
import ec.cleantok.ordenes.extraccion.Linea
import ec.cleantok.ordenes.extraccion.SeccionOrigen
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cada regla del parser SERCOP con un texto mínimo. */
class ReglasParserTest {

    private val lexico = Lexico.desdeClasspath()
    private val parser = ExtractorSercop(lexico)

    private fun doc(vararg lineas: String) = DocumentoPdf(lineas.map { Linea(1, it) }, 1)

    private fun materiales(texto: String, vararg extra: String) =
        parser.extraer(doc(" -    MATERIALES: $texto", *extra, " -    JORNADA: 40 horas")).items.filter { it.seccion == SeccionOrigen.MATERIALES }

    @Test
    fun `fusiona un fragmento huérfano con el anterior`() {
        val m = materiales("Alcohol, aromatizante, ambiental , cloro")
        assertEquals(listOf("Alcohol", "Aromatizante ambiental", "Cloro"), m.map { it.nombre })
        assertEquals(Confianza.MEDIA, m[1].confianza)
        assertEquals("aromatizante, ambiental", m[1].textoOriginal)
    }

    @Test
    fun `separa dos productos pegados tras una medida`() {
        val m = materiales("Hipoclorito de sodio al 10% Insecticida, Jabón")
        assertEquals(listOf("Hipoclorito de sodio al 10%", "Insecticida", "Jabón"), m.map { it.nombre })
        assertTrue(m.take(2).all { "posible separación" in it.motivos })
        // Una medida seguida de algo que no es producto no se separa.
        assertEquals(listOf("Mopa de 40 cm con mango"), materiales("Mopa de 40 cm con mango").map { it.nombre })
    }

    @Test
    fun `marca duplicados sin descartarlos`() {
        val m = materiales("insecticida de 600 ml, jabón, insecticida")
        assertEquals(3, m.size)
        assertEquals(0, m[2].duplicadoDe)
        assertTrue(m[2].motivos.single().startsWith("posible duplicado"))
    }

    @Test
    fun `los cortes de línea no parten un ítem`() {
        val m = materiales("guantes bicolor calibre", "25, fundas", "de basura negras")
        assertEquals(listOf("Guantes bicolor calibre 25", "Fundas de basura negras"), m.map { it.nombre })
    }

    @Test
    fun `descarta áreas y quita paréntesis de área`() {
        val texto = " -    HERRAMIENTAS Y MATERIALES: MATERIALES Y PRODUCTOS UTILIZADOS EN LA DESINFECCIÓN: Todos los materiales " +
            "serán suministrados por la Entidad Contratante • Cloro líquido (baños) • Pisos • Paredes • Manillas de puertas. ROTACIÓN DE PRODUCTOS • Agua"
        val r = parser.extraer(doc(texto))
        val m = r.items.filter { it.seccion == SeccionOrigen.MATERIALES }
        assertEquals(listOf("Cloro líquido"), m.map { it.nombre })
        assertEquals("Cloro líquido (baños)", m.single().textoOriginal)
        assertEquals(Abastece.ENTIDAD_CONTRATANTE, m.single().abastece)
    }

    @Test
    fun `una palabra suelta sin producto queda en BAJA y no seleccionada`() {
        val m = materiales("ambiental, cloro")
        assertEquals(Confianza.BAJA, m[0].confianza)
        assertFalse(m[0].seleccionado)
        assertEquals(Confianza.ALTA, m[1].confianza)
    }

    @Test
    fun `presentación, unidad y categoría sugeridas`() {
        val m = materiales("Papel higiénico jumbo de 250 mtrs, fundas negras 23x28, insecticida de 600 ml, Cloro 5.5%, guantes de nitrilo")
        assertEquals(listOf("250 mtrs", "23x28", "600 ml", "5.5%", null), m.map { it.presentacion })
        assertEquals(listOf(Unidad.ROLLO, Unidad.PAQUETE, Unidad.UNIDAD, Unidad.L, Unidad.PAR), m.map { it.unidad })
        assertEquals(listOf(Categoria.PAPEL_Y_HIGIENE, Categoria.BOLSAS, Categoria.QUIMICO, Categoria.QUIMICO, Categoria.EPP), m.map { it.categoria })
    }

    @Test
    fun `normaliza el nombre sin tocar el texto original`() {
        val m = materiales("Desengrasante Líquido, cloro liquido, jabon")
        assertEquals(listOf("Desengrasante líquido", "Cloro líquido", "Jabón"), m.map { it.nombre })
        assertEquals(listOf("Desengrasante Líquido", "cloro liquido", "jabon"), m.map { it.textoOriginal })
    }

    @Test
    fun `cláusulas de la entidad - lista con alias y marca de existentes`() {
        val r = parser.extraer(
            doc(
                " -    MATERIALES: Jabón, Cloro",
                " -    TAREAS: Reposición de material proporcionado por la entidad (servilletas (papel toalla), jabón) Vaciado",
                "El servicio de limpieza no incluye artículos de aseo como: papel higiénico, y gel desinfectante.",
            ),
        )
        val porNombre = r.items.associateBy { it.nombre }
        assertEquals(Abastece.ENTIDAD_CONTRATANTE, porNombre.getValue("Jabón").abastece) // ya existía: se marca
        assertEquals(SeccionOrigen.MATERIALES, porNombre.getValue("Jabón").seccion)
        assertEquals(Abastece.EMPRESA_LIMPIEZA, porNombre.getValue("Cloro").abastece)
        val clausula = r.items.filter { it.seccion == SeccionOrigen.CLAUSULA_ENTIDAD }.map { it.nombre }
        assertEquals(listOf("Papel toalla", "Papel higiénico", "Gel desinfectante"), clausula)
    }

    @Test
    fun `herramientas y maquinaria van como sugerencias no seleccionadas`() {
        val r = parser.extraer(
            doc(
                " -    MAQUINARIA: * Abrillantadora, Aspiradora Industrial",
                " -    HERRAMIENTAS: Coche escurridor 1 por cada 3000 m². Escalera 1 por cada 5000 m².",
                " -    EQUIPAMIENTO: *Uniforme *Guantes de caucho",
            ),
        )
        assertEquals(listOf("Abrillantadora", "Aspiradora industrial", "Coche escurridor", "Escalera", "Guantes de caucho"), r.items.map { it.nombre })
        assertTrue(r.items.none { it.seleccionado || it.esConsumible })
        assertEquals(Categoria.MAQUINARIA, r.items[0].categoria)
    }

    @Test
    fun `limpieza de ruido del portal y de la columna Cant`() {
        assertEquals("Oficio Circular No.", LectorPdf.limpiarLinea("Oficio Circular No. (12"))
        assertEquals("SERCOP-CDI En los productos las", LectorPdf.limpiarLinea("SERCOP-CDI En los productos las meses)"))
        assertEquals("plazo de (12 meses)", LectorPdf.limpiarLinea("plazo de (12 meses)"))
        val unidas = LectorPdf.unirCortes(listOf(Linea(1, "desinfec-"), Linea(1, "tante líquido")))
        assertEquals("desinfectante líquido", unidas.single().texto)
    }

    @Test
    fun `cabecera sin datos no rompe`() {
        val c = parser.cabecera(doc("texto cualquiera"))
        assertNull(c.numeroOrden)
        assertNull(c.areaM2)
    }

    @Test
    fun `enmascara correos, RUC, teléfonos y cuentas antes de enviar a la IA`() {
        val t = "Correo: ana.perez@ejemplo.ec RUC: 0999999998001 Tel 07-4000000 cel 0991234567 cuenta 1234567 cloro al 10%"
        val m = Enmascarador.enmascarar(t)
        assertFalse(listOf("ana.perez", "0999999998001", "4000000", "0991234567", "1234567").any { it in m }, m)
        assertTrue("cloro al 10%" in m)
    }
}
