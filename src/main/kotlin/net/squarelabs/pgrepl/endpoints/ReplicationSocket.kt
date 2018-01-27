package net.squarelabs.pgrepl.endpoints

import com.google.gson.Gson
import com.google.inject.Inject
import com.google.inject.Singleton
import net.squarelabs.pgrepl.messages.*
import net.squarelabs.pgrepl.model.ClientTxn
import net.squarelabs.pgrepl.model.Snapshot
import net.squarelabs.pgrepl.services.*
import org.eclipse.jetty.util.log.Log
import org.postgresql.core.BaseConnection
import java.util.*
import javax.websocket.*

@Singleton
class ReplicationSocket @Inject constructor(
        val replSvc: ReplicationService,
        val cfgSvc: ConfigService,
        val snapSvc: SnapshotService,
        val conSvc: ConnectionService,
        val crudSvc: CrudService
) : Endpoint(), MessageHandler.Whole<String> {

    companion object {
        private val LOG = Log.getLogger(ReplicationSocket::class.java)
    }

    private val msgTypes = hashMapOf(
            "SUBSCRIBE_REQUEST" to SubscribeRequest::class.java,
            "COMMIT" to CommitMsg::class.java,
            "MULTI_COMMIT" to MultiCommit::class.java,
            "PING" to PingRequest::class.java,
            "SNAPSHOT_REQUEST" to SnapshotRequest::class.java
    )

    private var session: Session? = null
    private var remote: RemoteEndpoint.Async? = null
    private var clientId: UUID? = null

    // ---------------------------------------- websocket events ------------------------------------------------------
    override fun onOpen(session: Session, config: EndpointConfig) {
        try {
            this.session = session
            this.remote = session.asyncRemote
            session.addMessageHandler(this)
            LOG.info("WebSocket Connect: {}", session.id)
        } catch (ex: Exception) {
            LOG.warn("Error opening websocket!", ex) // TODO: Error handling
        }
    }

    override fun onMessage(json: String) {
        try {
            val mapper = Gson()
            val baseMsg = mapper.fromJson(json, Message::class.java)
            val clazz = msgTypes[baseMsg.type] ?: throw Exception("Unknown message: ${baseMsg.type}")
            val msg = mapper.fromJson(json, clazz)
            when (msg) {
                is SubscribeRequest -> handleSubscribe(msg, mapper)
                is CommitMsg -> handleTxns(listOf(msg.txn), mapper)
                is MultiCommit -> handleTxns(msg.txns, mapper)
                is PingRequest -> handlePing(mapper)
                is SnapshotRequest -> handleSnapshotReq(mapper)
                else -> throw Exception("Unknown message: ${msg::class}")
            }
        } catch (ex: Exception) {
            LOG.warn("Error handling message!", ex)
            replSvc.unsubscribe(cfgSvc.getAppDbName(), clientId!!)
            session!!.close()
        }
    }

    override fun onError(session: Session?, cause: Throwable?) {
        super.onError(session, cause)
        LOG.warn("WebSocket Error", cause) // TODO: Error handling
        // TODO: Unsubscribe from replSvc
    }

    override fun onClose(session: Session?, close: CloseReason?) {
        super.onClose(session, close)
        this.session = null
        this.remote = null
        LOG.info("WebSocket Close: {} - {}", close!!.closeCode, close.reasonPhrase)
        replSvc.unsubscribe(cfgSvc.getAppDbName(), clientId!!)
    }

    // --------------------------------------- message handlers -------------------------------------------------------
    private fun handlePing(mapper: Gson) {
        remote!!.sendText(mapper.toJson(PongResponse()))
    }

    private fun handleSnapshotReq(mapper: Gson) {
        conSvc.getConnection(cfgSvc.getAppDbUrl()).use { con ->
            val snap = snapSvc.takeSnapshot(con.unwrap(BaseConnection::class.java))
            val response = SnapshotResponse(snap)
            remote!!.sendText(mapper.toJson(response))
        }
    }

    private fun handleSubscribe(req: SubscribeRequest, mapper: Gson) {
        clientId = UUID.fromString(req.clientId)
        val dbName = cfgSvc.getAppDbName()
        replSvc.subscribe(dbName, clientId!!, req.lsn, { json -> handlePgTxn(json) })
        remote!!.sendText(mapper.toJson(SubscribeResponse(null)))
    }

    private fun handleTxns(txns: List<ClientTxn>, mapper: Gson) {
        conSvc.getConnection(cfgSvc.getAppDbUrl()).use { con ->
            val snap = snapSvc.takeSnapshot(con, false)
            con.autoCommit = false
            txns.forEach { tryHandleTxn(it, con, snap, mapper) }
        }
    }

    private fun tryHandleTxn(txn: ClientTxn, con: BaseConnection, snap: Snapshot, mapper: Gson) {
        try {
            handleTxn(txn, con, snap)
            con.commit()
        } catch (ex: Exception) {
            con.rollback()
            val failMsg = ClientTxn(txn.id, 0, ArrayList())
            remote!!.sendText(mapper.toJson(TxnMsg(failMsg)))
        }
    }

    private fun handleTxn(txn: ClientTxn, con: BaseConnection, snap: Snapshot) {
        txn.changes.forEach({ change ->
            when (change.type) {
                "INSERT" -> crudSvc.insertRow(change.table, change.record, con)
                "UPDATE" -> crudSvc.updateRow(change.table, change.record, con, snap)
                "DELETE" -> crudSvc.deleteRow(change.table, change.record, con, snap)
                else -> throw Exception("Unknown change type: ${change.type}")
            }
        })
        crudSvc.updateTxnMap(txn.id, con)
    }

    // ------------------------------------- events from postgres -----------------------------------------------------
    fun handlePgTxn(json: String) {
        if (remote != null) remote!!.sendText(json)
    }

}