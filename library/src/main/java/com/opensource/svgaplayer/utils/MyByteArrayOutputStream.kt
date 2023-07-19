package com.opensource.svgaplayer.utils

import java.io.ByteArrayOutputStream

/**
 * 创建者      Created by xzy
 * 创建时间    2023/7/19
 */
class MyByteArrayOutputStream(var size: Int = 32) : ByteArrayOutputStream(size) {

    override fun reset() {
        super.reset()
        buf = ByteArray(0)
    }

    fun toUnSafeByteArray(): ByteArray {
        if (count == buf.size) {
            return buf
        }
        return toByteArray()
    }
}