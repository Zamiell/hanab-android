package live.hanab.client

import live.hanab.client.net.HanabJson
import live.hanab.client.net.HistoryRow
import live.hanab.client.protocol.GamePage
import live.hanab.client.protocol.ProtocolJson
import live.hanab.client.net.parseScores
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileApiTest {
    // Trimmed from a real hanab.live /scores page: the facts, then the grid.
    private val page = """
        <ul>
          <li>
            <span class="stat-description">Date joined:</span>
            March 31st, 2020
          </li>
          <li>
            <span class="stat-description">Games played (in non-speedruns):</span>
            484 (418 normally finished)
          </li>
          <li>
            <span class="stat-description"
              >Time spent playing (in non-speedruns):</span
            >

              6 days, 23 hours, and 27 minutes

          </li>
          <li>
            <span class="stat-description">Total max scores:</span>
            10 &nbsp;(0.1%)
          </li>
        </ul>
        <table class="history">
          <thead><tr><th>Variant</th><th>Total Games</th></tr></thead>
          <tbody>
            <tr>
              <td><a href="/variant/0">No Variant</a></td>
              <td class="center">393</td>
              <td class="center"><strong>25 / 25</strong>&nbsp;<i class="fas fa-check score-modifier green"></i></td>
              <td class="center"><strong>25 / 25</strong></td>
              <td class="center">23 /
                25</td>
              <td class="center">0 /
                25</td>
              <td class="center">0 / 25</td>
              <td class="center">23.1</td>
              <td class="center">9.7%</td>
            </tr>
            <tr>
              <td><a href="/variant/2">Black (6 Suits)</a></td>
              <td class="center">4</td>
              <td class="center">0 / 30</td>
              <td class="center">29 / 30</td>
              <td class="center">24 / 30</td>
              <td class="center">0 / 30</td>
              <td class="center">0 / 30</td>
              <td class="center">-</td>
              <td class="center">100%</td>
            </tr>
            <tr><td>not a full row</td></tr>
          </tbody>
        </table>
    """.trimIndent()

    @Test fun readsTheFacts() {
        val p = parseScores(page)!!
        assertEquals("Date joined" to "March 31st, 2020", p.facts[0])
        assertEquals("Games played (in non-speedruns)" to "484 (418 normally finished)", p.facts[1])
        assertEquals("6 days, 23 hours, and 27 minutes", p.facts[2].second)
        assertEquals("10 (0.1%)", p.facts[3].second)
    }

    @Test fun readsTheGridAndSkipsBrokenRows() {
        val rows = parseScores(page)!!.rows
        assertEquals(2, rows.size)
        val first = rows[0]
        assertEquals("No Variant", first.variant)
        assertEquals(393, first.games)
        assertEquals(listOf(25, 25, 23, 0, 0), first.best.map { it.score })
        assertEquals(25, first.best[0].max)
        assertEquals(23.1, first.average!!, 0.001)
        assertEquals(9.7, first.strikeoutRate!!, 0.001)
        assertNull(rows[1].average)
        assertEquals(100.0, rows[1].strikeoutRate!!, 0.001)
        assertEquals(30, rows[1].best[1].max)
    }

    @Test fun aPageWithoutTheGridIsNotAProfile() {
        assertNull(parseScores("<html>Error</html>"))
    }

    @Test fun readsAHistoryPageAsTheSiteSendsIt() {
        val json = """{"games":[{"gameId":2063346,"options":{"variantId":2197,"speedrun":false,"deckPlays":false,"emptyClues":false,"oneExtraCard":false,"oneLessCard":false,"allOrNothing":false,"detrimentalCharacters":false},"seed":"p2v2197s7","score":25,"numTurns":40,"endCondition":"normal","startedAt":"2026-10-25T01:30:00.000Z","finishedAt":"2026-10-25T01:44:52.123Z","numGamesOnSeed":8,"playerNames":["RandP","WillFlame"],"tags":[]}],"offset":0,"total":7298}"""
        val page = ProtocolJson.decodeFromString(GamePage.serializer(), json)
        assertEquals(7298L, page.total)
        val row = HistoryRow.of(page.games.single())
        assertEquals(2063346, row.id)
        assertEquals(2197, row.variant)
        assertEquals(2, row.numPlayers)
        assertEquals(25, row.score)
        assertEquals("2026-10-25", row.date)
        assertEquals(listOf("RandP"), row.others("WillFlame"))
    }

    /** The grid lost its average and strikeout columns, and shows a dash for a table size nobody played. */
    @Test fun readsTheGridWithoutTheAverageColumns() {
        val html = """
            <table class="history"><thead></thead><tbody>
              <tr>
                <td><a href="/variant/15">4 Suits</a></td><td class="center">4</td>
                <td class="center"><strong >20 / 20</strong ><i class="fas fa-check score-modifier green"></i ></td>
                <td class="center"> &ndash; </td><td class="center"> 0 / 20 </td>
                <td class="center"> &ndash; </td><td class="center"> &ndash; </td>
              </tr>
            </tbody></table>
        """.trimIndent()
        val row = parseScores(html)!!.rows.single()
        assertEquals("4 Suits", row.variant)
        assertEquals(4, row.games)
        assertEquals(listOf(20, 0, 0, 0, 0), row.best.map { it.score })
        assertEquals(listOf(20, 20, 20, 20, 20), row.best.map { it.max })
        assertNull(row.average)
        assertNull(row.strikeoutRate)
    }
}
