package com.smartdebt.manager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var db: Database
    private lateinit var root: LinearLayout
    private lateinit var summary: TextView
    private lateinit var search: EditText
    private lateinit var list: LinearLayout
    private val money = NumberFormat.getNumberInstance(Locale("ar","YE"))
    private val importPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { inspectImport(it) } }
    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { saveExport(it) } }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); db=Database(this); PDFBoxResourceLoader.init(applicationContext); renderHome() }
    private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()
    private fun button(text:String, action:()->Unit)=Button(this).apply { this.text=text;setOnClickListener{action()} }
    private fun text(s:String,size:Float=14f)=TextView(this).apply { text=s;textSize=size;setTextColor(0xFF20352B.toInt());setPadding(dp(4),dp(8),dp(4),dp(8)) }
    private fun field(hintText:String,value:String="")=EditText(this).apply { hint=hintText;setText(value);textSize=15f;setPadding(dp(12),dp(10),dp(12),dp(10)) }
    private fun renderHome() {
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(20),dp(16),dp(12));setBackgroundColor(0xFFF4F7F5.toInt());layoutDirection=View.LAYOUT_DIRECTION_RTL }
        root.addView(text("◈ مدير الديون الذكي",24f))
        root.addView(text("إدارة العملاء والديون والحوالات — بيانات محلية",12f))
        summary=text("");root.addView(summary)
        val actions=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        actions.addView(button("＋ عميل"){customerForm(null)},LinearLayout.LayoutParams(0,dp(52),1f))
        actions.addView(button("＋ دفعة"){choosePaymentCustomer()},LinearLayout.LayoutParams(0,dp(52),1f))
        root.addView(actions)
        val actions2=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        actions2.addView(button("استيراد"){importPicker.launch(arrayOf("text/*","application/json","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","application/vnd.ms-excel","application/pdf","application/octet-stream"))},LinearLayout.LayoutParams(0,dp(48),1f))
        actions2.addView(button("تصدير CSV"){exportPicker.launch("smart-debt-customers.csv")},LinearLayout.LayoutParams(0,dp(48),1f))
        root.addView(actions2)
        search=field("ابحث باسم العميل أو الهاتف");search.addTextChangedListener(SimpleWatcher { renderCustomers() });root.addView(search)
        root.addView(text("العملاء والأرصدة",18f))
        list=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val scroll=ScrollView(this);scroll.addView(list);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root);refresh()
    }
    private fun refresh(){ summary.text="الديون المستحقة: ${fmt(db.totalDebt())} ر.ي   •   المدفوع: ${fmt(db.totalPaid())} ر.ي   •   العملاء: ${db.customers().size}";renderCustomers() }
    private fun fmt(n:Double)=money.format(n)
    private fun renderCustomers(){
        if(!::list.isInitialized)return
        list.removeAllViews()
        val cs=db.customers(search.text.toString())
        if(cs.isEmpty()){list.addView(text("لا يوجد عملاء مطابقون. أضف عميلًا جديدًا."));return}
        cs.forEach { c ->
            val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(8));setBackgroundColor(0xFFFFFFFF.toInt()) }
            card.addView(text(c.name+"  •  "+c.phone,17f))
            card.addView(text("الرصيد المستحق: ${fmt(db.balance(c.id))} ر.ي",15f))
            val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
            row.addView(button("السجل"){customerHistory(c.id)},LinearLayout.LayoutParams(0,dp(44),1f))
            row.addView(button("دفعة"){paymentForm(c.id)},LinearLayout.LayoutParams(0,dp(44),1f))
            row.addView(button("SMS"){sendSms(c)},LinearLayout.LayoutParams(0,dp(44),1f))
            row.addView(button("تعديل"){customerForm(c)},LinearLayout.LayoutParams(0,dp(44),1f))
            card.addView(row);list.addView(card);list.addView(View(this).apply { setBackgroundColor(0xFFE0E8E3.toInt()) },LinearLayout.LayoutParams(-1,dp(1)))
        }
    }
    private fun customerForm(c:Customer?){
        val name=field("اسم العميل",c?.name.orEmpty());val phone=field("رقم الهاتف",c?.phone.orEmpty());val debt=field("الدين الأصلي",c?.originalDebt?.toString().orEmpty());debt.inputType=8194
        val notes=field("ملاحظات",c?.notes.orEmpty())
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(20),dp(20),dp(20));addView(text(if(c==null)"إضافة عميل" else "تعديل العميل",20f));addView(name);addView(phone);addView(debt);addView(notes);addView(button("حفظ"){ val n=name.text.toString().trim();val p=phone.text.toString().trim();val d=debt.text.toString().toDoubleOrNull()?:0.0
            if(n.isBlank()||p.isBlank()||d<0){toast("أدخل الاسم والهاتف ودينًا صحيحًا");return@button}
            if(c==null)db.addCustomer(n,p,d,notes.text.toString()) else db.updateCustomer(c.id,n,p,d,notes.text.toString())
            (itContext()).let{}; refresh();showHomeDialogClose()
        });addView(button("إلغاء"){showHomeDialogClose()}) }
        showDialogView(box)
    }
    private fun itContext()=this
    private var activeDialog: android.app.AlertDialog?=null
    private fun showDialogView(v:View){activeDialog=android.app.AlertDialog.Builder(this).setView(v).create();activeDialog?.show()}
    private fun showHomeDialogClose(){activeDialog?.dismiss();activeDialog=null;refresh()}
    private fun choosePaymentCustomer(){val cs=db.customers();if(cs.isEmpty()){toast("أضف عميلًا أولًا");return};android.app.AlertDialog.Builder(this).setTitle("اختر العميل").setItems(cs.map{"${it.name} — ${fmt(db.balance(it.id))} ر.ي"}.toTypedArray()){_,which->paymentForm(cs[which].id)}.show()}
    private fun paymentForm(customerId:Long){
        val c=db.customer(customerId)?:return
        val amount=field("المبلغ المستلم");amount.inputType=8194
        val date=field("التاريخ YYYY-MM-DD",SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date()))
        val ref=field("رقم الحوالة / مرجع فريد (اختياري)")
        val memo=field("اسم الصرافة أو ملاحظات")
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(20),dp(20),dp(20));addView(text("تسجيل دفعة — ${c.name}",19f));addView(text("الرصيد الحالي: ${fmt(db.balance(c.id))} ر.ي"));addView(amount);addView(date);addView(ref);addView(memo);addView(button("تسجيل الدفعة"){val a=amount.text.toString().toDoubleOrNull();if(a==null||a<=0){toast("أدخل مبلغًا صحيحًا");return@button};try{db.addPayment(c.id,a,date.text.toString(),ref.text.toString(),memo.text.toString());showHomeDialogClose();toast("تم تسجيل الدفعة وتحديث الرصيد")}catch(e:Exception){toast("تعذر التسجيل: قد يكون رقم الحوالة مسجلًا مسبقًا")}});addView(button("إلغاء"){showHomeDialogClose()})}
        showDialogView(box)
    }
    private fun customerHistory(id:Long){val c=db.customer(id)?:return;val items=db.payments(id);val body=buildString { append("العميل: ${c.name}\nالهاتف: ${c.phone}\nالدين الأصلي: ${fmt(c.originalDebt)} ر.ي\nالمدفوع: ${fmt(c.originalDebt-db.balance(id))} ر.ي\nالرصيد: ${fmt(db.balance(id))} ر.ي\n\nسجل الدفعات:\n");if(items.isEmpty())append("لا توجد دفعات");items.forEach{append("\n${it.date} — ${fmt(it.amount)} ر.ي — ${it.reference} — ${it.memo}")} };android.app.AlertDialog.Builder(this).setTitle("سجل العميل").setMessage(body).setPositiveButton("إغلاق",null).show()}
    private fun sendSms(c:Customer){if(db.balance(c.id)<=0){toast("لا يوجد رصيد مستحق");return};val body="السلام عليكم ${c.name}، نذكّركم بأن الرصيد المستحق لديكم ${fmt(db.balance(c.id))} ر.ي. شاكرين لكم تعاونكم.";val i=Intent(Intent.ACTION_SENDTO,Uri.parse("smsto:"+Uri.encode(c.phone))).putExtra("sms_body",body);try{startActivity(i)}catch(e:Exception){toast("لا يوجد تطبيق رسائل متاح")}}
    private fun inspectImport(uri:Uri){lifecycleScope.launch { val result=withContext(Dispatchers.IO){try{
        val name=uri.lastPathSegment.orEmpty().lowercase(Locale.ROOT)
        val display=contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0).orEmpty() else ""}.orEmpty().lowercase(Locale.ROOT)
        when{
            display.endsWith(".xlsx")||display.endsWith(".xls")->contentResolver.openInputStream(uri)!!.use{input->WorkbookFactory.create(input).use{wb->buildString{append("Excel: ${wb.numberOfSheets} أوراق.\n");for(i in 0 until wb.numberOfSheets){append("الورقة ${wb.getSheetAt(i).sheetName}:\n");var n=0;for(row in wb.getSheetAt(i)){append((0 until row.lastCellNum.coerceAtLeast(0).toInt()).joinToString(","){row.getCell(it)?.toString().orEmpty()}).append('\n');if(++n>=30){append("… معاينة أول 30 صفًا.\n");break}}}}}}
            display.endsWith(".pdf")->contentResolver.openInputStream(uri)!!.use{PDDocument.load(it).use{doc->"PDF (${doc.numberOfPages} صفحات)\n"+PDFTextStripper().getText(doc).take(50000)+"\n\nالمعاينة فقط؛ PDF المصور يحتاج OCR."}}
            display.endsWith(".db")||display.endsWith(".sqlite")||display.endsWith(".sqlite3")->{
                val tmp=java.io.File(cacheDir,"picked.db");contentResolver.openInputStream(uri)!!.use{input->tmp.outputStream().use{input.copyTo(it)}}
                val sq=android.database.sqlite.SQLiteDatabase.openDatabase(tmp.path,null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
                try{val tables=mutableListOf<String>();sq.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",null).use{cur->while(cur.moveToNext())tables.add(cur.getString(0))};"SQLite: الجداول المكتشفة\n"+tables.joinToString("\n") }finally{sq.close();tmp.delete()}
            }
            else->contentResolver.openInputStream(uri)!!.bufferedReader(Charsets.UTF_8).use{it.readText().take(50000)}
        }
    }catch(e:Exception){"تعذر فحص الملف: ${e.message}"}};android.app.AlertDialog.Builder(this).setTitle("معاينة الاستيراد").setMessage(result).setPositiveButton("إغلاق",null).show() } }
    private fun saveExport(uri:Uri){try{contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use{it.write("\uFEFF"+db.exportCsv())};toast("تم تصدير CSV")}catch(e:Exception){toast("فشل التصدير: ${e.message}")}}
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
}
