package com.surveyme.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Nominatim reverse answer to a [com.surveyme.core.FetchCity], from a real Toronto answer. */
@RunWith(RobolectricTestRunner::class)
class CityLookupTest {

    private val lookup = CityLookup()

    private val toronto = """
        {"place_id":351444040,"osm_type":"relation","osm_id":324211,"lat":"43.6534817","lon":"-79.3839347",
         "category":"boundary","type":"administrative","place_rank":12,"addresstype":"city","name":"Toronto",
         "display_name":"Toronto, Ontario, Canada",
         "boundingbox":["43.5796082","43.8554425","-79.6392832","-79.1132193"]}
    """.trimIndent()

    @Test
    fun `a city answer becomes the relation and its box, south north west east`() {
        val city = lookup.parse(toronto)
        assertEquals(324211L, city.relationId)
        assertEquals("Toronto", city.name)
        assertEquals(43.5796082, city.south, 1e-9)
        assertEquals(43.8554425, city.north, 1e-9)
        assertEquals(-79.6392832, city.west, 1e-9)
        assertEquals(-79.1132193, city.east, 1e-9)
        assertEquals("city:324211", city.areaKey)
        assertTrue(city.contains(43.70, -79.40))
        assertFalse(city.contains(43.70, -79.70))
    }

    @Test
    fun `a node answer, an error and a box over 25 square degrees are refused`() {
        expectNoCity(toronto.replace("\"relation\"", "\"node\""))
        expectNoCity("""{"error":"Unable to geocode"}""")
        expectNoCity(toronto.replace("\"43.5796082\",\"43.8554425\"", "\"40.0\",\"50.0\"").replace("\"-79.6392832\",\"-79.1132193\"", "\"-85.0\",\"-75.0\""))
    }

    private fun expectNoCity(json: String) {
        try {
            lookup.parse(json)
            fail("expected NoCityException for $json")
        } catch (e: CityLookup.NoCityException) {
            // expected
        }
    }
}
