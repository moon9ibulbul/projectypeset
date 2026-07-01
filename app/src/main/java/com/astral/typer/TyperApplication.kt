package com.astral.typer

import android.app.Application

class TyperApplication : Application() {
    companion object {
        lateinit var instance: TyperApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
