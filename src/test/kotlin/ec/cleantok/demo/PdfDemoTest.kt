package ec.cleantok.demo

import ec.cleantok.catalogo.dominio.Abastece
import ec.cleantok.ordenes.extraccion.ExtractorSercop
import ec.cleantok.ordenes.extraccion.LectorPdf
import ec.cleantok.ordenes.extraccion.Lexico
import ec.cleantok.ordenes.extraccion.SeccionOrigen
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PDF de ejemplo para la demo (`demo/orden-demo-sercop.pdf`): imita el formato de una orden de catálogo
 * electrónico del SERCOP con datos 100 % ficticios. Esta prueba lo genera y comprueba que el parser lo lee bien.
 */
class PdfDemoTest {

    private val paginas = listOf(
        listOf(
            "Catálogo electrónico https://catalogo.compraspublicas.gob.ec/ordenes/imprimir?id=0000000...",
            "ORDEN DE COMPRA POR CATÁLOGO ELECTRÓNICO",
            "Orden de Fecha de Fecha de",
            "CE-20260009999999 01-10-2026 02-10-2026",
            "compra: emisión: aceptación:",
            "DATOS DEL PROVEEDOR",
            "Razón social: EMPRESA DE LIMPIEZA DEMO S.A.        RUC: 0999999999001",
            "Correo electrónico: contacto@ejemplo.ec     Teléfono: 0900000000",
            "DATOS DE LA ENTIDAD CONTRATANTE",
            "Entidad GOBIERNO AUTONOMO DEMO",
            "contratante: DE PRUEBA RUC: 0999999998001 Teléfono:",
            "Persona que autoriza: Funcionario Demo",
            "1 de 2 01/10/2026, 10:00",
        ),
        listOf(
            "Catálogo electrónico https://catalogo.compraspublicas.gob.ec/ordenes/imprimir?id=0000000...",
            "DETALLE",
            "CPC Descripción Cant V. Descuento Sub. Total Impuesto V. Total Partida",
            "853300012 LIMPIEZA DE INTERIORES TIPO II 1250 1,2900 0,0000 1.612,5000 15,0000 1.854,3750 530209",
            " -    TIEMPO MINIMO DE CONTRATO: Oficio Circular No. (12",
            "SERCOP-CDI-2017-0020 las órdenes de compra por un período mínimo de meses)",
            " -    TAREAS BÁSICAS DEL PERSONAL DEL SERVICIO DE LIMPIEZA: Limpieza de pisos.",
            "Reposición de material proporcionado por la entidad (servilletas (papel toalla), papel",
            "higiénico, jabón líquido, fundas de basura) Vaciado de papeleras.",
            " -    MAQUINARIA: * Abrillantadora, Aspiradora Industrial, Hidrolavadora",
            " -    HERRAMIENTAS: * Coche Escurridor, Coche Utilitario, Escalera",
            " -    MATERIALES: Balde de 12 lt., Escoba de coco, Trapeador jumbo, Guantes de nitrilo,",
            "Recogedor de basura, Paño microfibra, Cloro liquido 5.5%, Desinfectante aromaterapia,",
            "aromatizante, ambiental , Detergente industrial, Alcohol antiséptico, Limpiavidrios,",
            "Hipoclorito de sodio al 10% Insecticida, insecticida de 600 ml, Fundas de basura negras",
            "23x28 medianas, atomizador.",
            " -    JORNADA: hasta 40 horas a la semana",
            "Subtotal 1.612,5000",
            "2 de 2 01/10/2026, 10:00",
        ),
    )

    private fun generar(): ByteArray {
        val salida = ByteArrayOutputStream()
        PDDocument().use { doc ->
            val fuente = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            paginas.forEach { lineas ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(fuente, 9f)
                    cs.setLeading(13f)
                    cs.newLineAtOffset(40f, 800f)
                    lineas.forEach { cs.showText(it); cs.newLine() }
                    cs.endText()
                }
            }
            doc.save(salida)
        }
        return salida.toByteArray()
    }

    @Test
    fun `el PDF de la demo se genera y el parser lo lee completo`() {
        val bytes = generar()
        File("demo").mkdirs()
        File("demo/orden-demo-sercop.pdf").writeBytes(bytes)

        val parser = ExtractorSercop(Lexico.desdeClasspath())
        val doc = LectorPdf.leer(bytes)
        assertTrue(parser.esSercop(doc))
        val r = parser.extraer(doc)
        val materiales = r.items.filter { it.seccion == SeccionOrigen.MATERIALES }.map { it.nombre }
        assertEquals(
            listOf(
                "Balde de 12 lt.", "Escoba de coco", "Trapeador jumbo", "Guantes de nitrilo", "Recogedor de basura", "Paño microfibra",
                "Cloro líquido 5.5%", "Desinfectante aromaterapia", "Aromatizante ambiental", "Detergente industrial",
                "Alcohol antiséptico", "Limpiavidrios", "Hipoclorito de sodio al 10%", "Insecticida", "Insecticida de 600 ml",
                "Fundas de basura negras 23x28 medianas", "Atomizador",
            ),
            materiales,
        )
        val entidad = r.items.filter { it.seccion == SeccionOrigen.CLAUSULA_ENTIDAD }
        assertEquals(listOf("Papel toalla", "Papel higiénico", "Jabón líquido", "Fundas de basura"), entidad.map { it.nombre })
        assertTrue(entidad.all { it.abastece == Abastece.ENTIDAD_CONTRATANTE })
        assertEquals("CE-20260009999999", r.cabecera.numeroOrden)
        assertEquals("GOBIERNO AUTONOMO DEMO DE PRUEBA", r.cabecera.entidadContratante)
        assertEquals(12, r.cabecera.vigenciaMeses)
    }
}
