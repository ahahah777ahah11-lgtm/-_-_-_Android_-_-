package com.smartdebt.manager
import android.text.Editable
import android.text.TextWatcher
class SimpleWatcher(private val changed:(String)->Unit):TextWatcher {
 override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
 override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){changed(s?.toString().orEmpty())}
 override fun afterTextChanged(s:Editable?){}
}
