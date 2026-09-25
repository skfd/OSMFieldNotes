package com.surveyme.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OsmElementRefTest {

    @Test
    fun `parses the overpass poi id`() {
        assertEquals(OsmElementRef("node", 123), OsmElementRef.fromPoiId("overpass_node_123"))
        assertEquals(OsmElementRef("way", 4567), OsmElementRef.fromPoiId("overpass_way_4567"))
        assertEquals(OsmElementRef("relation", 89), OsmElementRef.fromPoiId("overpass_relation_89"))
    }

    @Test
    fun `rejects ids that are not elements`() {
        assertNull(OsmElementRef.fromPoiId("osm_note_123"))
        assertNull(OsmElementRef.fromPoiId("overpass_area_1"))
        assertNull(OsmElementRef.fromPoiId("overpass_node_abc"))
        assertNull(OsmElementRef.fromPoiId("xoverpass_node_1"))
        assertNull(OsmElementRef.of("node", null))
        assertNull(OsmElementRef.of(null, 1))
        assertNull(OsmElementRef.of("node", 0))
        assertNull(OsmElementRef.of("changeset", 5))
    }

    @Test
    fun `structured columns win over the id string but the id string is the fallback`() {
        assertEquals(OsmElementRef("way", 7), OsmElementRef.of("Way", 7))
        assertEquals("way7", OsmElementRef("way", 7).longForm)
    }

    @Test
    fun `finds the first element link in a note thread`() {
        val text = "See https://www.openstreetmap.org/way/12345 and later https://osm.org/node/6"
        assertEquals(OsmElementRef("way", 12345), OsmElementRef.firstInText(text))
        assertEquals(OsmElementRef("node", 6), OsmElementRef.firstInText("http://osm.org/node/6/history"))
        assertEquals(OsmElementRef("relation", 9), OsmElementRef.firstInText("https://openstreetmap.org/relation/9#map=19"))
        assertNull(OsmElementRef.firstInText("https://www.openstreetmap.org/note/12345"))
        assertNull(OsmElementRef.firstInText("no link here"))
        assertNull(OsmElementRef.firstInText(null))
    }

    @Test
    fun `builds the osm org and web editor urls`() {
        val ref = OsmElementRef("node", 123)
        assertEquals("https://www.openstreetmap.org/node/123", ref.browseUrl)
        assertEquals("https://www.openstreetmap.org/edit?node=123", ref.editUrl)
        assertEquals("https://www.openstreetmap.org/edit?way=5", OsmElementRef("way", 5).editUrl)
    }

    @Test
    fun `josm remote url puts longitudes left and right, latitudes top and bottom`() {
        val url = OsmElementRef("node", 123).josmRemoteUrl(lat = 43.65, lon = -79.38, halfSizeDeg = 0.001)
        assertTrue(url.startsWith("http://127.0.0.1:8111/load_and_zoom?"))
        val params = url.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals("-79.381000", params["left"])
        assertEquals("-79.379000", params["right"])
        assertEquals("43.651000", params["top"])
        assertEquals("43.649000", params["bottom"])
        assertEquals("node123", params["select"])
        assertTrue(params["left"]!!.toDouble() < params["right"]!!.toDouble())
        assertTrue(params["bottom"]!!.toDouble() < params["top"]!!.toDouble())
    }

    @Test
    fun `geo uri is locale independent`() {
        assertEquals("geo:43.650000,-79.380000?z=19", OsmElementRef.geoUri(43.65, -79.38))
    }
}
