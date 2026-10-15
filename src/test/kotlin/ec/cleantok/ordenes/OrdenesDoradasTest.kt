package ec.cleantok.ordenes

import ec.cleantok.catalogo.dominio.Abastece
import ec.cleantok.comun.Textos
import ec.cleantok.ordenes.extraccion.Confianza
import ec.cleantok.ordenes.extraccion.ExtractorSercop
import ec.cleantok.ordenes.extraccion.LectorPdf
import ec.cleantok.ordenes.extraccion.Lexico
import ec.cleantok.ordenes.extraccion.ResultadoExtraccion
import ec.cleantok.ordenes.extraccion.SeccionOrigen
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pruebas doradas del parser SERCOP con las 3 órdenes reales (src/test/resources/ordenes/).
 * Contienen datos personales y no se versionan: si no están, las pruebas se saltan.
 */
class OrdenesDoradasTest {

    private val lexico = Lexico.desdeClasspath()
    private val parser = ExtractorSercop(lexico)

    private fun extraer(nombre: String): ResultadoExtraccion {
        val f = File("src/test/resources/ordenes/$nombre")
        assumeTrue(f.exists(), "Falta el PDF de prueba $nombre")
        val doc = LectorPdf.leer(f.readBytes())
        assertTrue(parser.esSercop(doc))
        return parser.extraer(doc)
    }

    private fun normal(l: Collection<String>) = l.map(Textos::normalizar)

    private fun materialesEmpresa(r: ResultadoExtraccion) =
        r.items.filter { it.seccion == SeccionOrigen.MATERIALES && it.abastece == Abastece.EMPRESA_LIMPIEZA }

    @Test
    fun `orden B - CNIG 2019 - 29 materiales y 4 de la entidad`() {
        val r = extraer("orden-b-cnig-2019.pdf")
        val esperados = listOf(
            "Balde de 5 litros", "Bomba de destape cañerías", "Cepillo mano de oso", "Escoba cerda suave", "Escoba de coco",
            "Espátula", "Esponjas", "Guantes", "Lana de acero", "Lija de agua", "Limpiones", "Mascarilla",
            "Recogedor de basura", "Trapeador para baños", "Trapeador de oficinas", "Toalla para baños (limpión)", "Alcohol",
            "Antisarro", "Crema lustra muebles", "Desengrasante líquido", "Desinfectante", "Desodorante ambiental líquido",
            "Desodorante ambiental en pastilla", "Destapa cañerías", "Detergente industrial", "Hipoclorito de sodio al 10%",
            "Insecticida", "Limpiavidrios", "Atomizador",
        )
        val mats = materialesEmpresa(r)
        assertEquals(normal(esperados), normal(mats.map { it.nombre }), "Materiales B")

        val fusion = mats.single { it.nombre == "Desodorante ambiental en pastilla" }
        assertEquals(Confianza.MEDIA, fusion.confianza)
        assertTrue("posible fusión" in fusion.motivos)
        assertEquals("Desodorante, ambiental en pastilla", fusion.textoOriginal)
        val separados = mats.filter { "posible separación" in it.motivos }.map { it.nombre }
        assertEquals(listOf("Hipoclorito de sodio al 10%", "Insecticida"), separados)

        val entidad = r.items.filter { it.seccion == SeccionOrigen.CLAUSULA_ENTIDAD }
        assertEquals(normal(listOf("Papel toalla", "Papel higiénico", "Jabón", "Fundas de basura")), normal(entidad.map { it.nombre }))
        assertTrue(entidad.all { it.abastece == Abastece.ENTIDAD_CONTRATANTE && it.seleccionado })

        // Herramientas, maquinaria y equipamiento: sugerencias no seleccionadas ni consumibles.
        val otros = r.items.filter { it.seccion in setOf(SeccionOrigen.HERRAMIENTAS, SeccionOrigen.MAQUINARIA, SeccionOrigen.EQUIPAMIENTO) }
        assertTrue(otros.isNotEmpty() && otros.none { it.seleccionado || it.esConsumible })
        assertTrue(otros.any { it.nombre == "Abrillantadora" })

        val c = r.cabecera
        assertEquals("CE-20190001477721", c.numeroOrden)
        assertEquals(LocalDate.of(2019, 2, 6), c.fechaEmision)
        assertEquals("CONSEJO NACIONAL PARA LA IGUALDAD DE GENERO", c.entidadContratante)
        assertEquals("LIMPIEZA DE INTERIORES TIPO II", c.descripcionServicio)
        assertEquals(0, BigDecimal("809").compareTo(c.areaM2))
        assertEquals(12, c.vigenciaMeses)
    }

    @Test
    fun `orden C - MINTEL 2026 - 34 entradas con fusión, cortes de línea y duplicado`() {
        val r = extraer("orden-c-mintel-2026.pdf")
        val esperados = listOf(
            "Alcohol antiséptico", "Aromatizante ambiental", "Desinfectante aromaterapia", "Cloro líquido 5.5%", "Atomizador",
            "Mano de oso", "Bomba para baños", "Detergente", "Escoba de pluma", "Escoba de coco", "Estropajo reforzado color verde",
            "Paño microfibra", "Fundas de basura negras 23x28 medianas", "Fundas de basura negras industriales 30x36",
            "Fundas de basura blancas pequeñas 18x24", "Guantes bicolor calibre 25", "Insecticida de 600 ml",
            "Lavavajilla de 450 gr", "Aceite rojo para limpiar muebles", "Tela repuesto de mopa para barrido 40cm",
            "Estructura de mopa 40 cm", "Tela repuesto de mopa para trapeo 40cm", "Squash y extensión", "Recogedores de basura",
            "Trapeadores jumbo", "Guantes de nitrilo", "Lija de agua", "Balde de 12 lt.", "Jabón líquido para dispensador",
            "Papel higiénico jumbo de 250 mtrs doble hoja blanco", "Papel toalla de mano", "Insecticida", "Abono orgánico",
            "Mopas para limpieza de vidrio de 35 cm",
        )
        val mats = materialesEmpresa(r)
        assertEquals(normal(esperados), normal(mats.map { it.nombre }), "Materiales C")
        assertEquals("Cloro líquido 5.5%", mats.single { it.nombre.startsWith("Cloro") }.nombre)

        val arom = mats.single { it.nombre == "Aromatizante ambiental" }
        assertTrue("posible fusión" in arom.motivos)
        val dup = mats.last { it.nombre == "Insecticida" }
        assertEquals(Confianza.MEDIA, dup.confianza)
        assertTrue(dup.motivos.any { it.startsWith("posible duplicado") })
        assertEquals(mats.indexOfFirst { it.nombre == "Insecticida de 600 ml" }, dup.duplicadoDe)
        assertEquals("guantes bicolor calibre 25", Textos.normalizar(mats.single { "bicolor" in it.nombre }.textoOriginal!!))
        assertEquals("23x28", mats.single { "23x28" in it.nombre }.presentacion)

        assertTrue(r.items.none { it.seccion == SeccionOrigen.CLAUSULA_ENTIDAD })
        val herramientas = r.items.filter { it.seccion == SeccionOrigen.HERRAMIENTAS }
        assertTrue(herramientas.any { it.nombre == "Coche escurridor" } && herramientas.none { it.seleccionado })

        val c = r.cabecera
        assertEquals("CE-20260003108887", c.numeroOrden)
        assertEquals(LocalDate.of(2026, 9, 14), c.fechaEmision)
        assertEquals("MINISTERIO DE INFRAESTRUCTURA Y TECNOLOGIA", c.entidadContratante)
        assertEquals("LIMPIEZA DE INTERIORES Y EXTERIORES TIPO III", c.descripcionServicio)
        assertEquals(0, BigDecimal("7133").compareTo(c.areaM2))
        assertEquals(12, c.vigenciaMeses)
    }

    @Test
    fun `orden A - Universidad de Cuenca 2021 - materiales de la entidad sin áreas`() {
        val r = extraer("orden-a-ucuenca-2021.pdf")
        val mats = r.items.filter { it.seccion == SeccionOrigen.MATERIALES }
        val esperados = listOf(
            "Cloro líquido", "Bactericida fungicida al 0.5%", "Líquido antisarro", "Cera antideslizante", "Líquido limpiavidrios",
            "Limpiador de muebles", "Desengrasante", "Detergente", "Agua destilada", "Alcohol al 70%", "Amonio cuaternario",
        )
        assertEquals(normal(esperados), normal(mats.map { it.nombre }), "Materiales A")
        assertTrue(mats.all { it.abastece == Abastece.ENTIDAD_CONTRATANTE })

        val clausula = r.items.filter { it.seccion == SeccionOrigen.CLAUSULA_ENTIDAD }
        assertEquals(
            normal(listOf("Papel higiénico", "Fundas de basura para tachos", "Toalla de manos", "Jabón de baño", "Gel desinfectante")),
            normal(clausula.map { it.nombre }),
        )
        assertTrue(clausula.all { it.abastece == Abastece.ENTIDAD_CONTRATANTE })

        val areas = listOf("pisos", "banos", "paredes", "techos", "puertas", "ventanas", "persianas", "mesones", "escritorios", "mobiliario", "manillas")
        val propuestos = (mats + clausula).map { Textos.normalizar(it.nombre) }
        assertTrue(propuestos.none { p -> areas.any { p.startsWith(it) } }, "No debe proponer áreas: $propuestos")

        val herramientas = r.items.filter { it.seccion == SeccionOrigen.HERRAMIENTAS }
        assertTrue(herramientas.isNotEmpty() && herramientas.none { it.seleccionado })
        assertTrue(herramientas.any { it.nombre.startsWith("Letrero de precaución") })
        assertTrue(herramientas.none { lexico.empiezaConArea(it.nombre) })

        assertEquals("CE-20210001971827", r.cabecera.numeroOrden)
        assertEquals(LocalDate.of(2021, 3, 5), r.cabecera.fechaEmision)
        assertEquals("UNIVERSIDAD DE CUENCA", r.cabecera.entidadContratante)
        assertTrue(r.cabecera.descripcionServicio!!.startsWith("Servicio de Limpieza Hospitalaria"))
        assertEquals(0, BigDecimal("6956").compareTo(r.cabecera.areaM2))
    }
}
