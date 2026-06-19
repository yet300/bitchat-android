package com.app.common.utils

import co.touchlab.kermit.Logger

object Log {

    inline fun d(tag: String, message: String) =
        Logger.d(messageString = message, tag = tag)

    inline fun d(tag: String, message: String, throwable: Throwable) =
        Logger.d(messageString = message, throwable = throwable, tag = tag)


    inline fun e(tag: String, message: String) =
        Logger.e(messageString = message, tag = tag)

    inline fun e(tag: String, message: String, throwable: Throwable) =
        Logger.e(messageString = message, throwable = throwable, tag = tag)


    inline fun i(tag: String, message: String) =
        Logger.i(messageString = message, tag = tag)

    inline fun i(tag: String, message: String, throwable: Throwable) =
        Logger.i(messageString = message, throwable = throwable, tag = tag)


    inline fun v(tag: String, message: String) =
        Logger.v(messageString = message, tag = tag)


    inline fun v(tag: String, message: String, throwable: Throwable) =
        Logger.v(messageString = message, throwable = throwable, tag = tag)


    inline fun w(tag: String, message: String) =
        Logger.w(messageString = message, tag = tag)


    inline fun w(tag: String, message: String, throwable: Throwable) =
        Logger.w(messageString = message, throwable = throwable, tag = tag)

}