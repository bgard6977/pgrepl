package net.squarelabs.pgrepl

import com.google.inject.Guice
import net.squarelabs.pgrepl.client.ReplicationClient
import net.squarelabs.pgrepl.model.Snapshot
import net.squarelabs.pgrepl.services.ConfigService
import net.squarelabs.pgrepl.services.ConnectionService
import net.squarelabs.pgrepl.services.DbService
import net.squarelabs.pgrepl.services.SnapshotService
import org.flywaydb.core.Flyway
import org.junit.Assert
import org.junit.Test
import java.net.URI
import java.util.concurrent.TimeUnit

class AppTest {

    private val injector = Guice.createInjector(DefaultInjector())!!
    private val app: App = injector.getInstance(App::class.java)
    private val conSvc = injector.getInstance(ConnectionService::class.java)!!
    private val cfgSvc = injector.getInstance(ConfigService::class.java)!!
    private val snapSvc = injector.getInstance(SnapshotService::class.java)!!

    @Test
    fun shouldReceiveInitialState() {
        // setup
        val conString = cfgSvc.getJdbcDatabaseUrl()
        conSvc.getConnection(conString).use {
            val dbName = cfgSvc.getAppDbName()
            val url = cfgSvc.getJdbcDatabaseUrl()
            DbService(url, conSvc).use {
                if (it.list().contains(dbName)) it.drop(dbName)
                it.create(dbName)
                val flyway = Flyway()
                flyway.setDataSource(cfgSvc.getAppDbUrl(), null, null)
                flyway.migrate()
            }
        }
        var expected : Snapshot? = null
        conSvc.getConnection(cfgSvc.getAppDbUrl()).use {
            val sql = "INSERT INTO person (id, name) VALUES (1, 'Brent');"
            it.prepareStatement(sql).use {
                it.executeUpdate()
            }
            expected = snapSvc.takeSnapshot(it)
        }
        app.use {
            // exercise
            it.start()
            var actual: Snapshot? = null
            object : ReplicationClient(URI("ws://localhost:8080/echo")) {
                override fun onSnapshot(snapshot: Snapshot) {
                    super.onSnapshot(snapshot)
                    actual = snapshot
                }
            }
            while (actual == null) TimeUnit.MILLISECONDS.sleep(10)
            expected = expected!!.copy(lsn = actual!!.lsn)

            // assert
            Assert.assertEquals("WebSocket should receive notifications", expected, actual)
        }

        // teardown
        injector.allBindings.values
                .map { it.provider.get() }
                .filter { it is AutoCloseable }
                .map { it as AutoCloseable }
                .forEach { it.close() }
        conSvc.audit()
    }
}