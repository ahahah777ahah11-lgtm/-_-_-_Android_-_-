package com.smartdebt.manager

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Customer(val id: Long, val name: String, val phone: String, val originalDebt: Double, val notes: String, val updatedAt: Long)
data class Payment(val id: Long, val customerId: Long, val amount: Double, val date: String, val reference: String, val memo: String)

class Database(context: Context) : SQLiteOpenHelper(context, "smart_debt.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE customers(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, phone TEXT NOT NULL, original_debt REAL NOT NULL DEFAULT 0, notes TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE payments(id INTEGER PRIMARY KEY AUTOINCREMENT, customer_id INTEGER NOT NULL, amount REAL NOT NULL, date TEXT NOT NULL, reference TEXT NOT NULL DEFAULT '', memo TEXT NOT NULL DEFAULT '', UNIQUE(reference), FOREIGN KEY(customer_id) REFERENCES customers(id))""")
        db.execSQL("CREATE INDEX idx_customers_phone ON customers(phone)")
        db.execSQL("CREATE INDEX idx_payments_customer ON payments(customer_id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun customers(search: String = ""): List<Customer> {
        val out = mutableListOf<Customer>()
        val q = "%${search.trim()}%"
        readableDatabase.rawQuery("SELECT * FROM customers WHERE name LIKE ? OR phone LIKE ? ORDER BY name COLLATE NOCASE", arrayOf(q,q)).use { c ->
            while(c.moveToNext()) out.add(Customer(c.getLong(0),c.getString(1),c.getString(2),c.getDouble(3),c.getString(4),c.getLong(5)))
        }; return out
    }
    fun customer(id: Long): Customer? = customers().firstOrNull { it.id == id }
    fun addCustomer(name: String, phone: String, debt: Double, notes: String): Long {
        val v=ContentValues().apply { put("name",name.trim());put("phone",phone.trim());put("original_debt",debt);put("notes",notes);put("updated_at",System.currentTimeMillis()) }
        return writableDatabase.insert("customers",null,v)
    }
    fun updateCustomer(id: Long,name:String,phone:String,debt:Double,notes:String) {
        val v=ContentValues().apply { put("name",name.trim());put("phone",phone.trim());put("original_debt",debt);put("notes",notes);put("updated_at",System.currentTimeMillis()) }
        writableDatabase.update("customers",v,"id=?",arrayOf(id.toString()))
    }
    fun deleteCustomer(id: Long) { writableDatabase.beginTransaction(); try { writableDatabase.delete("payments","customer_id=?",arrayOf(id.toString()));writableDatabase.delete("customers","id=?",arrayOf(id.toString()));writableDatabase.setTransactionSuccessful() } finally { writableDatabase.endTransaction() } }
    fun addPayment(customerId:Long, amount:Double,date:String,reference:String,memo:String):Long {
        val v=ContentValues().apply { put("customer_id",customerId);put("amount",amount);put("date",date);put("reference",reference.trim());put("memo",memo.trim()) }
        return writableDatabase.insertOrThrow("payments",null,v)
    }
    fun payments(customerId:Long):List<Payment> { val out=mutableListOf<Payment>();readableDatabase.rawQuery("SELECT * FROM payments WHERE customer_id=? ORDER BY date DESC,id DESC",arrayOf(customerId.toString())).use { c -> while(c.moveToNext())out.add(Payment(c.getLong(0),c.getLong(1),c.getDouble(2),c.getString(3),c.getString(4),c.getString(5))) };return out }
    fun balance(id:Long):Double { val c=customer(id)?:return 0.0;readableDatabase.rawQuery("SELECT COALESCE(SUM(amount),0) FROM payments WHERE customer_id=?",arrayOf(id.toString())).use { it.moveToFirst();return (c.originalDebt-it.getDouble(0)).coerceAtLeast(0.0) } }
    fun totalDebt():Double=customers().sumOf { balance(it.id) }
    fun totalPaid():Double { readableDatabase.rawQuery("SELECT COALESCE(SUM(amount),0) FROM payments",null).use { it.moveToFirst();return it.getDouble(0) } }
    fun overdueCustomers(days:Long=7):List<Customer> = customers().filter { balance(it.id)>0 && System.currentTimeMillis()-it.updatedAt > days*86400000L }
    fun importCustomerIfNew(name:String,phone:String,debt:Double,notes:String):Boolean {
        if(name.isBlank()||phone.isBlank()) return false
        val existing=customers().firstOrNull { it.phone.filter(Char::isDigit).takeLast(9)==phone.filter(Char::isDigit).takeLast(9) }
        if(existing!=null) return false
        addCustomer(name,phone,debt,notes);return true
    }
    fun exportCsv():String {
        val sb=StringBuilder("name,phone,original_debt,paid,balance,notes\r\n")
        customers().forEach { c ->
            val paid=c.originalDebt-balance(c.id)
            val row=listOf(c.name,c.phone,c.originalDebt.toString(),paid.toString(),balance(c.id).toString(),c.notes)
            sb.append(row.joinToString(",") { "\""+it.replace("\"","\"\"")+"\"" }).append("\r\n")
        };return sb.toString()
    }
}
