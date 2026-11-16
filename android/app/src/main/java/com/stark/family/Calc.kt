package com.stark.family

import java.util.Locale

/** Exact arithmetic for the agent: numbers are never guessed. Supports + - * / ( ) and a postfix %. */
object Calc {
    private val OK = Regex("^[0-9+\\-*/().% ]{1,80}$")

    fun eval(expr: String): Double? {
        if (!OK.matches(expr)) return null
        return try { P(expr.replace(" ", "")).parse() } catch (e: Exception) { null }
    }

    fun fmt(v: Double): String =
        if (v == Math.floor(v) && Math.abs(v) < 1e12) v.toLong().toString()
        else String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')

    private class P(val s: String) {
        var i = 0

        fun parse(): Double {
            val v = expr()
            if (i != s.length) throw IllegalArgumentException("trailing")
            return v
        }

        fun expr(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                val r = term()
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        fun term(): Double {
            var v = factor()
            while (i < s.length && (s[i] == '*' || s[i] == '/')) {
                val op = s[i++]
                val r = factor()
                if (op == '/' && r == 0.0) throw ArithmeticException("div0")
                v = if (op == '*') v * r else v / r
            }
            return v
        }

        fun factor(): Double {
            var v = unary()
            while (i < s.length && s[i] == '%') {
                i++
                v /= 100.0
            }
            return v
        }

        fun unary(): Double {
            if (s[i] == '-') { i++; return -unary() }
            if (s[i] == '+') { i++; return unary() }
            return atom()
        }

        fun atom(): Double {
            if (s[i] == '(') {
                i++
                val v = expr()
                if (i >= s.length || s[i] != ')') throw IllegalArgumentException("paren")
                i++
                return v
            }
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (st == i) throw IllegalArgumentException("num")
            return s.substring(st, i).toDouble()
        }
    }
}
