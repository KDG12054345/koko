package com.faust

import android.app.Application
import com.faust.database.FaustDatabase

class FaustApplication : Application() {
    val database by lazy { FaustDatabase.getDatabase(this) }
}
