package net.squarelabs.pgrepl.db

import net.squarelabs.pgrepl.services.ConfigService
import net.squarelabs.pgrepl.services.ConnectionService
import net.squarelabs.pgrepl.services.SlotService
import org.eclipse.jetty.util.log.Log
import org.postgresql.PGProperty
import org.postgresql.core.BaseConnection
import org.postgresql.replication.LogSequenceNumber
import org.postgresql.replication.PGReplicationStream
import java.nio.ByteBuffer
import java.sql.DriverManager
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class Replicator(
        val dbName: String,
        val clientId: UUID,
        val lsn: Long,
        val cfgService: ConfigService,
        val conSvc: ConnectionService
) : AutoCloseable {

    private val executor = Executors.newScheduledThreadPool(1)
    val plugin = "wal2json"
    val listeners = ArrayList<(Long, String) -> Unit>()
    val con: BaseConnection
    val stream: PGReplicationStream
    val future: Future<*>

    init {
        // Connect to DB
        val properties = Properties()
        PGProperty.ASSUME_MIN_SERVER_VERSION.set(properties, "9.4")
        PGProperty.REPLICATION.set(properties, "database")
        PGProperty.PREFER_QUERY_MODE.set(properties, "simple")
        val url = cfgService.getAppDbUrl() // TODO: Listen to dbName, not cfgService.AppDb
        con = DriverManager.getConnection(url, properties) as BaseConnection

        // Create a slot
        val slotName = "slot_${clientId.toString().replace("-", "_")}"
        SlotService(url, conSvc).use {
            it.drop(slotName)
            it.create(slotName, plugin)
        }

        // Start listening
        stream = con
                .replicationAPI
                .replicationStream()
                .logical()
                .withSlotName(slotName)
                .withStartPosition(LogSequenceNumber.valueOf(lsn))
                .withSlotOption("include-xids", true)
                // https://github.com/eulerto/wal2json/blob/master/wal2json.c
                // include-timestamp
                // include-schemas
                // include-types
                // include-type-oids
                // include-typmod
                // include-not-null
                // pretty-print
                // write-in-chunks
                .withSlotOption("include-lsn", true)
                .withStatusInterval(20, TimeUnit.SECONDS)
                .start()
        future = executor.scheduleAtFixedRate({ checkMessages() }, 0, 10, TimeUnit.MILLISECONDS)
    }

    fun checkMessages() {
        try {
            // LOG.info("------ read {}", clientId)
            var buffer = stream.readPending()
            while (buffer != null) {
                val lsn = stream.lastReceiveLSN
                val str = toString(buffer)
                listeners.forEach({ l -> l(lsn.asLong(), str) })
                stream.setAppliedLSN(lsn)
                stream.setFlushedLSN(lsn) // TODO: never flush?
                buffer = stream.readPending()
            }
        } catch (ex: Exception) {
            LOG.warn("Error reading from database for client ${clientId}", ex)
            close()
        }
    }

    fun addListener(listener: (Long, String) -> Unit) {
        listeners.add { lng, str -> listener(lng, str) }
    }

    override fun close() {
        LOG.info("Closing Replicator ${clientId}")
        future.cancel(true)
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)
        executor.shutdownNow()

        stream.close()
        con.close()
    }

    companion object {
        private val LOG = Log.getLogger(Replicator::class.java)

        // TODO: Helper method
        private fun toString(buffer: ByteBuffer): String {
            val offset = buffer.arrayOffset()
            val source = buffer.array()
            val length = source.size - offset

            return String(source, offset, length)
        }
    }

}