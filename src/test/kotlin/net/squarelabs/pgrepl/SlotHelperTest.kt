package net.squarelabs.pgrepl

import org.junit.Assert
import org.junit.Test

class SlotHelperTest {

    private val dbName = "pgrepl_test"

    @Test
    fun shouldCrud() {
        try {
            val conString = "jdbc:postgresql://localhost:5432/$dbName?user=postgres&password=postgres"
            SlotHelper(conString).use {
                if(it.list().contains(dbName)) it.drop(dbName)
                Assert.assertEquals("should not have test db after drop", false, it.list().contains(dbName))
                it.create(dbName, "test_decoding")
                Assert.assertEquals("should not test db after create", true, it.list().contains(dbName))
            }
        } catch (ex: Exception) {
            ex.printStackTrace() // Get woke, travis
            Assert.assertNull(ex)
        }
    }

}