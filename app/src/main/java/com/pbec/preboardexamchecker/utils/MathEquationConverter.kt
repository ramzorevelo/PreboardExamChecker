package com.pbec.preboardexamchecker.utils

import android.util.Log

object MathEquationConverter {

    private val EXCLUDED_FRACTION_UNITS = setOf(
        "g/ml", "km/h", "m/s", "n/m^2", "j/s", "w/m^2", "ft/s", "lb/ft",
        "lbf/ft^2", "lb/gal", "oz/gal", "gal/min", "btu/hr", "kcal/hr",
        "rev/s", "n/kg", "kg/m^3", "kg/s", "m/s^2", "n/m", "pa/s",
        "w/m", "j/kg", "m^3/s", "km/s", "rad/s", "rev/min", "g/cm^3",
        "kg/l", "lb/ft^3", "gal/s", "mol/l", "mol/m^3", "n/mm^2",
        "kw/m^2", "m/min", "km/min", "g/l", "j/m^2", "w/m^3", "v/m",
        "a/m^2", "cd/m^2", "ft/min", "lb/in^2", "oz/ft^2",
        "c/m^2", "f/m", "h/m", "s/m", "ω/m", "ω/m^2", "a/m",
        "wb/m^2", "v/a", "bit/s", "byte/s", "kbit/s", "mbit/s",
        "gbit/s", "hz/v", "mho/m", "mho/cm", "a/v", "μf/m",
        "nh/m", "t/m", "v/cm", "j/m^2", "db/m", "baud/s",
        "flops/s", "pf/m", "ma/cm^2", "lm/w", "bit/m",
        "kg/cm", "kj/s", "btu/lb", "ft/sec", "r/min", "m/se",
        "cu/m", "m/sec", "rad/sec", "rev/sec", "kg/cu.m",
        "m/sec^2", "kj/s", "kcal/s", "btu/s", "hp/s",
        "kg/cu.m", "kg/m^3", "lb/ft^3", "lb/cu.ft", "g/cm^3", "g/cu.cm",
        "kg/cu", "kg/cm^2", "m/sec^2", "m/s^2", "km/s", "ft/sec", "ft/s", "m/se",
        "rad/sec", "rev/sec"
    ).map { it.lowercase() }.toSet()

    private val CHEMICAL_FORMULAS = setOf(
        "NO_2", "NO_3", "SO_4", "CO_3", "PO_4", "OH", "NH_4", "H_2O",
        "Mg(NO_2)_2", "Mg(NO_3)_2", "CaSO_4", "NaCl", "KOH", "H_2SO_4",
        "HNO_3", "HCl", "Na_2CO_3", "Ca(OH)_2", "MgNO_2", "Mg(NO2)2",
        "CO_2", "Mg", "NO", "CO"
    ).map { it.lowercase() }.toSet()

    private val MATH_SYMBOLS = mapOf(
        "pi" to "\\pi", "ohm" to "\\Omega", "sum" to "\\sum", "integral" to "\\int",
        "log" to "\\log", "theta" to "\\theta", "alpha" to "\\alpha", "beta" to "\\beta",
        "gamma" to "\\gamma", "sin" to "\\sin", "cos" to "\\cos", "tan" to "\\tan",
        "ln" to "\\ln", "lim" to "\\lim", "infinity" to "\\infty", "approx" to "\\approx",
        "neq" to "\\neq"
    )

    private val UNDERSCORE_REGEX = Regex("_+")

    fun isUnderscoreOnly(text: String): Boolean = UNDERSCORE_REGEX.matches(text)

    fun convertEquationTextToLaTeX(text: String): String {
        Log.d("MathEquationConverter", "Processing input: $text")

        if (text.isBlank() || isUnderscoreOnly(text) || isCurrencyValue(text)) {
            Log.d("MathEquationConverter", "Empty, underscore-only, or currency input, returning as is: $text")
            return text
        }

        if (isChemicalFormula(text)) {
            var chemText = text
            chemText = chemText.replace("〖", "\\text{").replace("〗", "}")
            chemText = chemText.replace(Regex("""\\text\{([^}]*?)\}(_\{?\d+\}?)?""")) { matchResult ->
                val inner = matchResult.groupValues[1]
                val subscript = matchResult.groupValues[2]?.replace("{", "")?.replace("}", "")?.replace("_", "") ?: ""
                if (subscript.isNotEmpty()) "\\text{$inner}_{$subscript}" else "\\text{$inner}"
            }
            chemText = chemText.replace(Regex("""_+\{(\d+)\}"""), "_{$1}")
            Log.d("MathEquationConverter", "Chemical formula converted: $chemText")
            return chemText
        }

        var latexText = text
            .replace("⁰", "^0").replace("¹", "^1").replace("²", "^2").replace("³", "^3")
            .replace("⁴", "^4").replace("⁵", "^5").replace("⁶", "^6").replace("⁷", "^7")
            .replace("⁸", "^8").replace("⁹", "^9")
            .replace(Regex("""(\d+\.?\d*)\s*(kg/cm²|cm²|m²|s²|sec²|m/sec²|lb/ft|m/s²|ft/sec)""")) { it.value }

        latexText = latexText
            .replace("\\", "\\textbackslash{}")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace("&", "\\&")
            .replace("#", "\\#")
            .replace("~", "\\textasciitilde{}")
            .replace("<", "\\textless{}")
            .replace(">", "\\textgreater{}")
            .replace("%", "\\%")
            .replace(Regex("""_([a-zA-Z0-9]+)""")) { matchResult ->
                "_{${matchResult.groupValues[1]}}"
            }

        latexText = latexText.replace(Regex("""([a-zA-Z][a-zA-Z0-9_^{}]*|\d+\.?\d*)(\s*/\s*)([a-zA-Z][a-zA-Z0-9_^{}]*|\d+\.?\d*)""")) { matchResult ->
            val num = matchResult.groupValues[1].trim()
            val den = matchResult.groupValues[3].trim()
            val potentialUnit = "${num.lowercase()}/${den.lowercase()}"
            if (EXCLUDED_FRACTION_UNITS.contains(potentialUnit)) {
                Log.d("MathEquationConverter", "Skipping fraction, detected unit: $potentialUnit")
                matchResult.value
            } else {
                Log.d("MathEquationConverter", "Converting to fraction: $num/$den")
                "\\frac{$num}{$den}"
            }
        }

        MATH_SYMBOLS.forEach { (plain, latex) ->
            latexText = latexText.replace(Regex("""\b$plain\b(?![a-zA-Z0-9_^$[^$]*$])""", RegexOption.IGNORE_CASE)) { matchResult ->
                if (isUnitContext(latexText, matchResult.range.first)) matchResult.value else latex
            }
        }

        latexText = wrapMathExpressions(latexText)
        latexText = validateLaTeX(latexText, text)
        Log.d("MathEquationConverter", "Final LaTeX: $latexText")
        return latexText
    }

    private fun wrapMathExpressions(text: String): String {
        var result = text
        val mathRegex = Regex(
            """\\(?:frac\{[^{}]*\}\{[^{}]*\}|sin|cos|tan|log|ln|lim|sum|int|pi|Omega|theta|alpha|beta|gamma|infty|approx|neq)\{[^{}]*\}(?:\{[^{}]*\})?|\\text\{[^}]*\}|_[a-zA-Z0-9]+"""
        )
        result = mathRegex.replace(result) { matchResult ->
            if (isCurrencyValue(matchResult.value)) matchResult.value else "$${matchResult.value}$"
        }
        return result.replace(Regex("""\$[\s$]*\$"""), "")
    }

    private fun isUnitContext(text: String, index: Int): Boolean {
        if (index + 3 >= text.length) return true
        val nextChar = text.getOrNull(index + 3)
        return nextChar == null || nextChar.isWhitespace() || nextChar in listOf(',', '.', ';', ')', '}')
    }

    private fun validateLaTeX(text: String, original: String): String {
        var result = text
        result = result.replace(Regex("""\$[\s$]*\$"""), "")
        result = result.replace(Regex("""\$[^$]*\{[^}]*$"""), original)
        result = result.replace(Regex("""\$[^$]*\$[^$]*\$"""), original)
        result = result.replace(Regex("""\$\\frac\{([^{}]*)\}\{([^{}]*)\}\$""")) { matchResult ->
            val num = matchResult.groupValues[1]
            val den = matchResult.groupValues[2]
            if (num.isEmpty() || den.isEmpty()) {
                Log.w("MathEquationConverter", "Invalid fraction: \\frac{$num}{$den}, reverting to: $original")
                original
            } else {
                matchResult.value
            }
        }
        result = result.replace(Regex("""\$[a-zA-Z]+(?:\{[^}]*\})*\$""")) { matchResult ->
            try {
                val latexContent = matchResult.value.trim('$')
                if (latexContent.matches(Regex("""[a-zA-Z]+(?:\{[^}]*\})*"""))) {
                    matchResult.value
                } else {
                    Log.w("MathEquationConverter", "Invalid LaTeX: $latexContent, reverting to: $original")
                    original
                }
            } catch (e: Exception) {
                Log.w("MathEquationConverter", "Error validating LaTeX: ${matchResult.value.trim('$')}, error: ${e.message}, reverting to: $original")
                original
            }
        }
        return result
    }

    fun containsMathSyntax(text: String): Boolean {
        if (text.isBlank() || isCurrencyValue(text) || isUnderscoreOnly(text)) return false
        if (text.contains(Regex("[a-zA-Z]_([a-zA-Z0-9]+)"))) return true
        if (text.contains(Regex("[_^][a-zA-Z0-9]"))) return true
        if (text.contains(Regex("[a-zA-Z]+\\$"))) return true
        if (text.contains(Regex("""\\(?:pi|Omega|sum|int|log|theta|alpha|beta|gamma|sin|cos|tan|ln|lim|infty|approx|neq|frac)"""))) return true
        if (text.contains(Regex("[〖〗]"))) return true
        if (text.contains(Regex("""([a-zA-Z][a-zA-Z0-9_^{}]*|\d+\.?\d*)(\s*/\s*)([a-zA-Z][a-zA-Z0-9_^{}]*|\d+\.?\d*)"""))) {
            val match = Regex("""([a-zA-Z][a-zA-Z0-9_^{}]*|\d+\.?\d*)(\s*/\s*)([a-zA-Z][a-zA-Z0-9_^{}]*|\d+\.?\d*)""").find(text)
            if (match != null) {
                val num = match.groupValues[1].lowercase()
                val den = match.groupValues[3].lowercase()
                if (EXCLUDED_FRACTION_UNITS.contains("$num/$den")) return false
            }
            return true
        }
        MATH_SYMBOLS.keys.forEach { symbol ->
            if (Regex("""\b$symbol\b(?![a-zA-Z0-9_^$[^$]*$])""", RegexOption.IGNORE_CASE).find(text) != null) return true
        }
        return false
    }

    fun isCurrencyValue(text: String): Boolean {
        return Regex("""[P\$][\s]*[0-9,]+(?:\.[0-9]{1,2})?\b""").matches(text) ||
                text.contains(Regex("""[P\$][\s]*[0-9,]+(?:\.[0-9]{1,2})?\b"""))
    }

    fun isChemicalFormula(text: String): Boolean {
        val cleanText = text.replace("〖", "").replace("〗", "").replace(Regex("[^a-zA-Z0-9_()]+"), "").lowercase()
        return CHEMICAL_FORMULAS.contains(cleanText) ||
                cleanText.matches(Regex("[A-Z][a-z0-9]*(_[0-9]+)?(?:\\([A-Z][a-z0-9]*(_[0-9]+)?\\)(_?[0-9]+)?)?"))
    }

    fun splitMathParts(text: String): List<Pair<String, Boolean>> {
        if (!containsMathSyntax(text) && !isChemicalFormula(text)) {
            return listOf(Pair(text, false))
        }

        val parts = mutableListOf<Pair<String, Boolean>>()
        var current = StringBuilder()
        var i = 0

        while (i < text.length) {
            // Handle chemical formulas
            val remainingText = text.substring(i)
            if (isChemicalFormula(remainingText)) {
                if (current.isNotEmpty()) parts.add(Pair(current.toString(), false))
                current = StringBuilder()
                val start = i
                var depth = if (text[i] == '〖') 1 else 0
                i += if (text[i] == '〖') 1 else 0
                while (i < text.length) {
                    if (text[i] == '〖') depth++
                    else if (text[i] == '〗') depth--
                    else if (depth == 0 && !text[i].isLetterOrDigit() && text[i] !in "_(){}") break
                    i++
                }
                var chem = text.substring(start, i).replace("〖", "(").replace("〗", ")")
                if (i < text.length && text[i] == '_') {
                    chem += "_"
                    i++
                    while (i < text.length && (text[i].isDigit() || text[i] in "{}")) {
                        chem += text[i]
                        i++
                    }
                }
                parts.add(Pair(chem, true))
                current = StringBuilder()
                continue
            }
            // Handle sub/superscripts
            else if (text[i] == '_' || text[i] == '^') {
                if (current.isNotEmpty()) {
                    var baseStart = current.length - 1
                    while (baseStart >= 0 && current[baseStart].isLetterOrDigit()) baseStart--
                    baseStart++
                    val pre = current.substring(0, baseStart)
                    if (pre.isNotEmpty()) parts.add(Pair(pre, false))
                    current = StringBuilder(current.substring(baseStart) + text[i])
                } else {
                    current.append(text[i])
                }
                i++
                while (i < text.length && (text[i].isLetterOrDigit() || text[i] in "{}-")) {
                    current.append(text[i])
                    i++
                }
                parts.add(Pair(current.toString(), true))
                current = StringBuilder()
                continue
            }
            // Handle fractions and units
            else if (text[i] == '/' && i > 0 && i + 1 < text.length) {
                var numEnd = i
                var numStart = i - 1
                while (numStart >= 0 && (text[numStart].isLetterOrDigit() || text[numStart] in "_^{}.")) numStart--
                numStart++
                val num = text.substring(numStart, numEnd).trim()
                var denStart = i + 1
                var denEnd = denStart
                while (denEnd < text.length && (text[denEnd].isLetterOrDigit() || text[denEnd] in "_^{}.")) denEnd++
                val den = text.substring(denStart, denEnd).trim()
                val potentialUnit = "${num.lowercase()}/${den.lowercase()}"
                val fullFraction = text.substring(numStart, denEnd)
                if (num.isNotEmpty() && den.isNotEmpty() && !EXCLUDED_FRACTION_UNITS.contains(potentialUnit)) {
                    val pre = current.toString()
                    if (pre.isNotEmpty()) parts.add(Pair(pre, false))
                    parts.add(Pair(fullFraction, true))
                    current = StringBuilder()
                    i = denEnd
                    continue
                } else {
                    current.append(text.substring(numStart, denEnd))
                    i = denEnd
                    continue
                }
            }
            current.append(text[i])
            i++
        }
        if (current.isNotEmpty()) parts.add(Pair(current.toString(), false))
        return parts.filter { it.first.isNotEmpty() }
    }
    fun getExcludedFractionUnits(): Set<String> = EXCLUDED_FRACTION_UNITS
}