package com.example.rumiologia.asistente.ia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pruebas de la regla que decide el alcance de la búsqueda.
 *
 * <p>Los siete equipos son los reales de {@code ml/clases.json}. El caso que motivó
 * estas pruebas: el laboratorio tiene DOS estufas, y una regla ingenua basada en
 * palabras del nombre levantaría el filtro al preguntar "por la estufa", mezclándolas.
 */
public class ReglaDeAlcanceTest {

    private ReglaDeAlcance regla;

    @Before
    public void preparar() {
        Map<String, String> equipos = new LinkedHashMap<>();
        equipos.put("ankom_200_fiber_analyzer", "Analizador de Fibra ANKOM 200");
        equipos.put("ankom_daisy_incubator", "Incubadora ANKOM DAISY");
        equipos.put("ankom_estufa", "Estufa de Secado ANKOM");
        equipos.put("aquasearcher_ab33m1", "Medidor Multiparámetro AQUASEARCHER AB33M1");
        equipos.put("contador_de_colonias", "Contador de Colonias");
        equipos.put("memmert", "Estufa Universal MEMMERT");
        equipos.put("ohaus_pr224", "Balanza Analítica OHAUS PR224");
        regla = new ReglaDeAlcance(equipos);
    }

    // ------------------------------------------------- palabras discriminantes

    @Test
    public void descartaLasPalabrasCompartidasPorVariosEquipos() {
        // "ankom" está en tres equipos y "estufa" en dos: no identifican a ninguno.
        assertFalse(regla.palabrasDe("ankom_estufa").contains("ankom"));
        assertFalse(regla.palabrasDe("ankom_estufa").contains("estufa"));
        assertFalse(regla.palabrasDe("memmert").contains("estufa"));
    }

    @Test
    public void conservaLasPalabrasPropiasDeCadaEquipo() {
        assertTrue(regla.palabrasDe("ankom_estufa").contains("secado"));
        assertTrue(regla.palabrasDe("memmert").contains("memmert"));
        assertTrue(regla.palabrasDe("ohaus_pr224").contains("balanza"));
        assertTrue(regla.palabrasDe("ankom_daisy_incubator").contains("daisy"));
    }

    @Test
    public void descartaLasPalabrasDemasiadoCortas() {
        // "200" y "de" aparecerían en cualquier frase.
        assertFalse(regla.palabrasDe("ankom_200_fiber_analyzer").contains("200"));
        assertFalse(regla.palabrasDe("contador_de_colonias").contains("de"));
    }

    // -------------------------------------------------- el filtro se mantiene

    @Test
    public void mantieneElFiltroConLaPreguntaAmbiguaSobreLaEstufa() {
        // El caso crítico: sin descartar "estufa", esto levantaría el filtro y la
        // respuesta mezclaría los 102 °C de la ANKOM con los 300 °C de la MEMMERT.
        assertEquals("ankom_estufa",
                regla.equipoParaFiltrar("ankom_estufa", "¿A qué temperatura trabaja la estufa?"));
    }

    @Test
    public void mantieneElFiltroAunqueSeNombreLaMarcaCompartida() {
        assertEquals("ankom_estufa",
                regla.equipoParaFiltrar("ankom_estufa", "¿Cómo enciendo la estufa ANKOM?"));
    }

    @Test
    public void mantieneElFiltroConPreguntasSinSujeto() {
        assertEquals("ankom_estufa",
                regla.equipoParaFiltrar("ankom_estufa", "¿cómo lo enciendo?"));
        assertEquals("ankom_daisy_incubator",
                regla.equipoParaFiltrar("ankom_daisy_incubator", "¿qué precauciones tiene?"));
    }

    // --------------------------------------------------- el filtro se levanta

    @Test
    public void levantaElFiltroSiSeNombraLaOtraEstufa() {
        assertNull(regla.equipoParaFiltrar("ankom_estufa", "¿y la estufa MEMMERT a cuánto llega?"));
    }

    @Test
    public void levantaElFiltroSiSePreguntaPorOtroEquipo() {
        assertNull(regla.equipoParaFiltrar("ohaus_pr224", "¿Cuánto dura la incubación en la DAISY?"));
        assertNull(regla.equipoParaFiltrar("ankom_estufa", "¿Cuál es la capacidad de la balanza?"));
        assertNull(regla.equipoParaFiltrar("ohaus_pr224", "¿Cómo uso el contador de colonias?"));
    }

    // ----------------------------------------------------------------- varios

    @Test
    public void sinEquipoDetectadoNoFiltra() {
        assertNull(regla.equipoParaFiltrar(null, "¿cómo enciendo la estufa?"));
        assertNull(regla.equipoParaFiltrar("", "¿cómo enciendo la estufa?"));
    }

    @Test
    public void ignoraTildesYMayusculas() {
        // La entrada por voz suele llegar sin tildes y en minúsculas.
        assertNull(regla.equipoParaFiltrar("ankom_estufa", "y el medidor multiparametro?"));
        assertNull(regla.equipoParaFiltrar("ankom_estufa", "Y EL MEDIDOR MULTIPARÁMETRO?"));
    }

    @Test
    public void identificaElEquipoMencionado() {
        assertEquals("memmert", regla.equipoMencionado("la memmert", "ankom_estufa"));
        assertNull(regla.equipoMencionado("cómo lo enciendo", "ankom_estufa"));
    }
}
