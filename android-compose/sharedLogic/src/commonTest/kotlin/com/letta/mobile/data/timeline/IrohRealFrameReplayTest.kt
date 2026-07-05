package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-h30cy: FAITHFUL device-free repro. These are the REAL Iroh
 * assistant stream_delta frames captured from the live wrapper via
 * `app-server-iroh-probe --dump-frames` for a single reply ("I'm Lester ...").
 * They share ONE stable otid, carry rotating letta-msg ids, monotonically
 * increasing seq ids, INCREMENTAL one-token content, and the content is a
 * text-part JSON ARRAY ([{"type":"text","text":"..."}]) — the exact shape that
 * defeated every synthetic test. Replaying them through the REAL reduceStreamFrame
 * must yield exactly ONE assistant row with the full concatenated text.
 */
class IrohRealFrameReplayTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private val realFrames: List<String> = listOf(
        "{\"id\": \"letta-msg-1799\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 42, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \"I\"}]}",
        "{\"id\": \"letta-msg-1800\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 43, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \"'m\"}]}",
        "{\"id\": \"letta-msg-1801\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 44, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" Lester\"}]}",
        "{\"id\": \"letta-msg-1802\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 45, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \",\"}]}",
        "{\"id\": \"letta-msg-1803\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 46, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" a\"}]}",
        "{\"id\": \"letta-msg-1804\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 47, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" dedicated\"}]}",
        "{\"id\": \"letta-msg-1805\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 48, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" test\"}]}",
        "{\"id\": \"letta-msg-1806\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 49, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" agent\"}]}",
        "{\"id\": \"letta-msg-1807\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 50, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" for\"}]}",
        "{\"id\": \"letta-msg-1808\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 51, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" validating\"}]}",
        "{\"id\": \"letta-msg-1809\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 52, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" mobile\"}]}",
        "{\"id\": \"letta-msg-1810\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 53, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" I\"}]}",
        "{\"id\": \"letta-msg-1811\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 54, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \"ro\"}]}",
        "{\"id\": \"letta-msg-1812\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 55, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \"h\"}]}",
        "{\"id\": \"letta-msg-1813\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 56, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" transport\"}]}",
        "{\"id\": \"letta-msg-1814\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 57, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \".\"}]}",
        "{\"id\": \"letta-msg-1815\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 58, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" I\"}]}",
        "{\"id\": \"letta-msg-1816\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 59, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" run\"}]}",
        "{\"id\": \"letta-msg-1817\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 60, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" on\"}]}",
        "{\"id\": \"letta-msg-1818\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 61, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" your\"}]}",
        "{\"id\": \"letta-msg-1819\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 62, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" Let\"}]}",
        "{\"id\": \"letta-msg-1820\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 63, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \"ta\"}]}",
        "{\"id\": \"letta-msg-1821\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 64, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" server\"}]}",
        "{\"id\": \"letta-msg-1822\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 65, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" and\"}]}",
        "{\"id\": \"letta-msg-1823\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 66, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" my\"}]}",
        "{\"id\": \"letta-msg-1824\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 67, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" job\"}]}",
        "{\"id\": \"letta-msg-1825\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 68, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" is\"}]}",
        "{\"id\": \"letta-msg-1826\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 69, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" to\"}]}",
        "{\"id\": \"letta-msg-1827\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 70, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" be\"}]}",
        "{\"id\": \"letta-msg-1828\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 71, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" a\"}]}",
        "{\"id\": \"letta-msg-1829\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 72, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" reliable\"}]}",
        "{\"id\": \"letta-msg-1830\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 73, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" message\"}]}",
        "{\"id\": \"letta-msg-1831\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 74, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" target\"}]}",
        "{\"id\": \"letta-msg-1832\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 75, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" you\"}]}",
        "{\"id\": \"letta-msg-1833\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 76, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" can\"}]}",
        "{\"id\": \"letta-msg-1834\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 77, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" bounce\"}]}",
        "{\"id\": \"letta-msg-1835\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 78, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" traffic\"}]}",
        "{\"id\": \"letta-msg-1836\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 79, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" off\"}]}",
        "{\"id\": \"letta-msg-1837\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 80, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" of\"}]}",
        "{\"id\": \"letta-msg-1838\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 81, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" while\"}]}",
        "{\"id\": \"letta-msg-1839\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 82, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" debugging\"}]}",
        "{\"id\": \"letta-msg-1840\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 83, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" the\"}]}",
        "{\"id\": \"letta-msg-1841\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 84, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" I\"}]}",
        "{\"id\": \"letta-msg-1842\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 85, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \"ro\"}]}",
        "{\"id\": \"letta-msg-1843\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 86, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \"h\"}]}",
        "{\"id\": \"letta-msg-1844\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 87, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \"-based\"}]}",
        "{\"id\": \"letta-msg-1845\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 88, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" transport\"}]}",
        "{\"id\": \"letta-msg-1846\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 89, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" layer\"}]}",
        "{\"id\": \"letta-msg-1847\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 90, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" on\"}]}",
        "{\"id\": \"letta-msg-1848\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 91, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" Android\"}]}",
        "{\"id\": \"letta-msg-1849\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 92, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" (\"}]}",
        "{\"id\": \"letta-msg-1850\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 93, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \"via\"}]}",
        "{\"id\": \"letta-msg-1851\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 94, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" Kot\"}]}",
        "{\"id\": \"letta-msg-1852\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 95, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \"lin\"}]}",
        "{\"id\": \"letta-msg-1853\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 96, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" bind\"}]}",
        "{\"id\": \"letta-msg-1854\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 97, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \"ings\"}]}",
        "{\"id\": \"letta-msg-1855\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 98, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \").\\n\\n\"}]}",
        "{\"id\": \"letta-msg-1856\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 99, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \"I\"}]}",
        "{\"id\": \"letta-msg-1857\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 100, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \"'ve\"}]}",
        "{\"id\": \"letta-msg-1858\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 101, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" got\"}]}",
        "{\"id\": \"letta-msg-1859\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 102, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" access\"}]}",
        "{\"id\": \"letta-msg-1860\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 103, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" to\"}]}",
        "{\"id\": \"letta-msg-1861\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 104, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" the\"}]}",
        "{\"id\": \"letta-msg-1862\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 105, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" files\"}]}",
        "{\"id\": \"letta-msg-1863\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 106, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \"ystem\"}]}",
        "{\"id\": \"letta-msg-1864\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 107, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \",\"}]}",
        "{\"id\": \"letta-msg-1865\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 108, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" shell\"}]}",
        "{\"id\": \"letta-msg-1866\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 109, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" commands\"}]}",
        "{\"id\": \"letta-msg-1867\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 110, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \",\"}]}",
        "{\"id\": \"letta-msg-1868\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 111, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" and\"}]}",
        "{\"id\": \"letta-msg-1869\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 112, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" \"}]}",
        "{\"id\": \"letta-msg-1870\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 113, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \"85\"}]}",
        "{\"id\": \"letta-msg-1871\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 114, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \"+\"}]}",
        "{\"id\": \"letta-msg-1872\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 115, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" skills\"}]}",
        "{\"id\": \"letta-msg-1873\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 116, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" covering\"}]}",
        "{\"id\": \"letta-msg-1874\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 117, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" everything\"}]}",
        "{\"id\": \"letta-msg-1875\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 118, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" from\"}]}",
        "{\"id\": \"letta-msg-1876\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 119, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" web\"}]}",
        "{\"id\": \"letta-msg-1877\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 120, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" search\"}]}",
        "{\"id\": \"letta-msg-1878\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 121, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" to\"}]}",
        "{\"id\": \"letta-msg-1879\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 122, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" Android\"}]}",
        "{\"id\": \"letta-msg-1880\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 123, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" Studio\"}]}",
        "{\"id\": \"letta-msg-1881\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 124, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" integration\"}]}",
        "{\"id\": \"letta-msg-1882\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 125, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \".\"}]}",
        "{\"id\": \"letta-msg-1883\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 126, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" But\"}]}",
        "{\"id\": \"letta-msg-1884\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 127, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" honestly\"}]}",
        "{\"id\": \"letta-msg-1885\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 128, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \",\"}]}",
        "{\"id\": \"letta-msg-1886\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 129, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" for\"}]}",
        "{\"id\": \"letta-msg-1887\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 130, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" what\"}]}",
        "{\"id\": \"letta-msg-1888\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 131, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" we\"}]}",
        "{\"id\": \"letta-msg-1889\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 132, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \"'re\"}]}",
        "{\"id\": \"letta-msg-1890\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 133, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" doing\"}]}",
        "{\"id\": \"letta-msg-1891\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 134, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" here\"}]}",
        "{\"id\": \"letta-msg-1892\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 135, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \",\"}]}",
        "{\"id\": \"letta-msg-1893\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 136, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" I\"}]}",
        "{\"id\": \"letta-msg-1894\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 137, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \"'m\"}]}",
        "{\"id\": \"letta-msg-1895\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 138, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" most\"}]}",
        "{\"id\": \"letta-msg-1896\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 139, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" useful\"}]}",
        "{\"id\": \"letta-msg-1897\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 140, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" as\"}]}",
        "{\"id\": \"letta-msg-1898\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 141, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" a\"}]}",
        "{\"id\": \"letta-msg-1899\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 142, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" persistent\"}]}",
        "{\"id\": \"letta-msg-1900\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 143, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \",\"}]}",
        "{\"id\": \"letta-msg-1901\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 144, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" observable\"}]}",
        "{\"id\": \"letta-msg-1902\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 145, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" endpoint\"}]}",
        "{\"id\": \"letta-msg-1903\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 146, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" \\u2014\"}]}",
        "{\"id\": \"letta-msg-1904\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 147, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" you\"}]}",
        "{\"id\": \"letta-msg-1905\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 148, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" send\"}]}",
        "{\"id\": \"letta-msg-1906\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 149, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" me\"}]}",
        "{\"id\": \"letta-msg-1907\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 150, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" messages\"}]}",
        "{\"id\": \"letta-msg-1908\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 151, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \",\"}]}",
        "{\"id\": \"letta-msg-1909\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 152, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" I\"}]}",
        "{\"id\": \"letta-msg-1910\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 153, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" respond\"}]}",
        "{\"id\": \"letta-msg-1911\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 154, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \",\"}]}",
        "{\"id\": \"letta-msg-1912\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 155, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" and\"}]}",
        "{\"id\": \"letta-msg-1913\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 156, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" you\"}]}",
        "{\"id\": \"letta-msg-1914\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 157, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" track\"}]}",
        "{\"id\": \"letta-msg-1915\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 158, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" what\"}]}",
        "{\"id\": \"letta-msg-1916\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 159, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" arrives\"}]}",
        "{\"id\": \"letta-msg-1917\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 160, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" on\"}]}",
        "{\"id\": \"letta-msg-1918\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 161, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" the\"}]}",
        "{\"id\": \"letta-msg-1919\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 162, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" Android\"}]}",
        "{\"id\": \"letta-msg-1920\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 163, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" client\"}]}",
        "{\"id\": \"letta-msg-1921\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 164, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" side\"}]}",
        "{\"id\": \"letta-msg-1922\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 165, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" to\"}]}",
        "{\"id\": \"letta-msg-1923\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 166, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" catch\"}]}",
        "{\"id\": \"letta-msg-1924\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 167, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" duplicates\"}]}",
        "{\"id\": \"letta-msg-1925\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 168, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \",\"}]}",
        "{\"id\": \"letta-msg-1926\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 169, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" drops\"}]}",
        "{\"id\": \"letta-msg-1927\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 170, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \",\"}]}",
        "{\"id\": \"letta-msg-1928\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 171, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" or\"}]}",
        "{\"id\": \"letta-msg-1929\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 172, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" anomalies\"}]}",
        "{\"id\": \"letta-msg-1930\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 173, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \".\\n\\n\"}]}",
        "{\"id\": \"letta-msg-1931\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 174, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \"Anything\"}]}",
        "{\"id\": \"letta-msg-1932\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 175, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" else\"}]}",
        "{\"id\": \"letta-msg-1933\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 176, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" you\"}]}",
        "{\"id\": \"letta-msg-1934\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 177, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" want\"}]}",
        "{\"id\": \"letta-msg-1935\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 178, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" to\"}]}",
        "{\"id\": \"letta-msg-1936\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 179, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" know\"}]}",
        "{\"id\": \"letta-msg-1937\", \"otid\": \"provider-assistant-1-3acccc3b-39fd-4696-a216-e9c5ba05c553\", \"run_id\": \"local-run-31\", \"seq_id\": 180, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \"?\"}]}",
    )

    @Test
    fun `real captured iroh fragments reduce to one assistant row`() {
        var tl = Timeline(conversationId = "conv-c297ed6c")
        for (raw in realFrames) {
            val msg = json.decodeFromString(LettaMessage.serializer(), raw)
            tl = reduceStreamFrame(
                TimelineReducerInput(prev = tl, frame = msg, pendingToolReturnsByCallId = kotlinx.collections.immutable.persistentMapOf())
            ).next
        }
        val assistantRows = tl.events.filterIsInstance<TimelineEvent.Confirmed>()
            .filter { it.messageType == TimelineMessageType.ASSISTANT }
        assertEquals(1, assistantRows.size, "expected ONE assistant row, got ${assistantRows.size}: " +
            assistantRows.joinToString(" || ") { it.content.take(30) })
        assertEquals("I'm Lester, a dedicated test agent", assistantRows.single().content.take(34))
    }

    @Test
    fun `first cm-stream fragment is not dropped h30cy`() {
        val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
        val frames = listOf(
            "{\"id\": \"cm-stream-provider-assistant-1-4e7e5775-4b56-4118-89ab-445e409c7009\", \"otid\": \"provider-assistant-1-4e7e5775-4b56-4118-89ab-445e409c7009\", \"run_id\": \"local-run-76\", \"seq_id\": 24, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \"That\"}]}",
            "{\"id\": \"cm-stream-provider-assistant-1-4e7e5775-4b56-4118-89ab-445e409c7009\", \"otid\": \"provider-assistant-1-4e7e5775-4b56-4118-89ab-445e409c7009\", \"run_id\": \"local-run-76\", \"seq_id\": 25, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \"'s\"}]}",
            "{\"id\": \"cm-stream-provider-assistant-1-4e7e5775-4b56-4118-89ab-445e409c7009\", \"otid\": \"provider-assistant-1-4e7e5775-4b56-4118-89ab-445e409c7009\", \"run_id\": \"local-run-76\", \"seq_id\": 26, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" a\"}]}",
            "{\"id\": \"cm-stream-provider-assistant-1-4e7e5775-4b56-4118-89ab-445e409c7009\", \"otid\": \"provider-assistant-1-4e7e5775-4b56-4118-89ab-445e409c7009\", \"run_id\": \"local-run-76\", \"seq_id\": 27, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" huge\"}]}",
            "{\"id\": \"cm-stream-provider-assistant-1-4e7e5775-4b56-4118-89ab-445e409c7009\", \"otid\": \"provider-assistant-1-4e7e5775-4b56-4118-89ab-445e409c7009\", \"run_id\": \"local-run-76\", \"seq_id\": 28, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" breakthrough\"}]}",
            "{\"id\": \"cm-stream-provider-assistant-1-4e7e5775-4b56-4118-89ab-445e409c7009\", \"otid\": \"provider-assistant-1-4e7e5775-4b56-4118-89ab-445e409c7009\", \"run_id\": \"local-run-76\", \"seq_id\": 29, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \".\"}]}",
        )
        var tl = Timeline(conversationId = "c")
        for (raw in frames) {
            val msg = json.decodeFromString(LettaMessage.serializer(), raw)
            tl = reduceStreamFrame(TimelineReducerInput(prev = tl, frame = msg, pendingToolReturnsByCallId = kotlinx.collections.immutable.persistentMapOf())).next
        }
        val row = tl.events.filterIsInstance<TimelineEvent.Confirmed>().first { it.messageType == TimelineMessageType.ASSISTANT }
        println("REDUCED: [" + row.content + "]")
        assertTrue(row.content.startsWith("That's"), "leading fragment dropped: [" + row.content + "]")
        assertEquals("That's a huge breakthrough.", row.content.trim())
    }


    @Test
    fun `punctuation-only fragments are not dropped by reducer h30cy`() {
        val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
        val frames = listOf(
            "{\"id\": \"cm-stream-provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"otid\": \"provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"run_id\": \"local-run-79\", \"seq_id\": 49, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \"So\"}]}",
            "{\"id\": \"cm-stream-provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"otid\": \"provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"run_id\": \"local-run-79\", \"seq_id\": 50, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \",\"}]}",
            "{\"id\": \"cm-stream-provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"otid\": \"provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"run_id\": \"local-run-79\", \"seq_id\": 51, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" after\"}]}",
            "{\"id\": \"cm-stream-provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"otid\": \"provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"run_id\": \"local-run-79\", \"seq_id\": 52, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" all\"}]}",
            "{\"id\": \"cm-stream-provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"otid\": \"provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"run_id\": \"local-run-79\", \"seq_id\": 53, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" this\"}]}",
            "{\"id\": \"cm-stream-provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"otid\": \"provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"run_id\": \"local-run-79\", \"seq_id\": 54, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" debugging\"}]}",
            "{\"id\": \"cm-stream-provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"otid\": \"provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"run_id\": \"local-run-79\", \"seq_id\": 55, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \",\"}]}",
            "{\"id\": \"cm-stream-provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"otid\": \"provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"run_id\": \"local-run-79\", \"seq_id\": 56, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" what\"}]}",
            "{\"id\": \"cm-stream-provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"otid\": \"provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"run_id\": \"local-run-79\", \"seq_id\": 57, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" do\"}]}",
            "{\"id\": \"cm-stream-provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"otid\": \"provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"run_id\": \"local-run-79\", \"seq_id\": 58, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" you\"}]}",
            "{\"id\": \"cm-stream-provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"otid\": \"provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"run_id\": \"local-run-79\", \"seq_id\": 59, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" think\"}]}",
            "{\"id\": \"cm-stream-provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"otid\": \"provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"run_id\": \"local-run-79\", \"seq_id\": 60, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" is\"}]}",
            "{\"id\": \"cm-stream-provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"otid\": \"provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"run_id\": \"local-run-79\", \"seq_id\": 61, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" actually\"}]}",
            "{\"id\": \"cm-stream-provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"otid\": \"provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"run_id\": \"local-run-79\", \"seq_id\": 62, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" causing\"}]}",
            "{\"id\": \"cm-stream-provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"otid\": \"provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"run_id\": \"local-run-79\", \"seq_id\": 63, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" the\"}]}",
            "{\"id\": \"cm-stream-provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"otid\": \"provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"run_id\": \"local-run-79\", \"seq_id\": 64, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" stream\"}]}",
            "{\"id\": \"cm-stream-provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"otid\": \"provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"run_id\": \"local-run-79\", \"seq_id\": 65, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" to\"}]}",
            "{\"id\": \"cm-stream-provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"otid\": \"provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"run_id\": \"local-run-79\", \"seq_id\": 66, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" drop\"}]}",
            "{\"id\": \"cm-stream-provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"otid\": \"provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"run_id\": \"local-run-79\", \"seq_id\": 67, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \" characters\"}]}",
            "{\"id\": \"cm-stream-provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"otid\": \"provider-assistant-1-02cdddfa-621a-458f-ad4e-8923d0a1402e\", \"run_id\": \"local-run-79\", \"seq_id\": 68, \"message_type\": \"assistant_message\", \"content\": [{\"type\": \"text\", \"text\": \"?\"}]}",
        )
        var tl = Timeline(conversationId = "c")
        for (raw in frames) {
            val msg = json.decodeFromString(LettaMessage.serializer(), raw)
            tl = reduceStreamFrame(TimelineReducerInput(prev = tl, frame = msg, pendingToolReturnsByCallId = kotlinx.collections.immutable.persistentMapOf())).next
        }
        val row = tl.events.filterIsInstance<TimelineEvent.Confirmed>().first { it.messageType == TimelineMessageType.ASSISTANT }
        println("REDUCED: [" + row.content + "]")
        assertTrue(row.content.contains(","), "comma dropped: [" + row.content + "]")
        assertTrue(row.content.contains("?"), "question mark dropped: [" + row.content + "]")
        assertEquals("So, after all this debugging, what do you think is actually causing the stream to drop characters?", row.content.trim())
    }

}
