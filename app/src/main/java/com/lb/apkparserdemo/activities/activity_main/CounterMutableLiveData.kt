package com.lb.apkparserdemo.activities.activity_main

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import androidx.annotation.AnyThread
import androidx.lifecycle.MutableLiveData

class CounterMutableLiveData(initialValue: Int = 0) : MutableLiveData<Int>(initialValue) {
    private val handler = Handler(Looper.getMainLooper())

    @AnyThread
    @SuppressLint("WrongThread")
    fun inc() {
        if (Thread.currentThread() == Looper.getMainLooper().thread) value += 1
        else handler.post {
            value = (value ?: 0) + 1
        }
    }

    override fun setValue(value: Int) {
        super.setValue(value)
    }

    override fun postValue(value: Int) {
        super.postValue(value)
    }

    override fun getValue(): Int {
        return super.getValue()!!
    }
}
