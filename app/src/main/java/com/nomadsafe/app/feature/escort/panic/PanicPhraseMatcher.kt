package com.nomadsafe.app.feature.escort.panic

import kotlin.math.max
import kotlin.math.min

/** Gravedad de la frase detectada: no todo grito merece un SOS. */
enum class PanicSeverity {
    /** Disparo inmediato del protocolo de emergencia completo. */
    SOS,

    /** Eleva la vigilancia: pide check-in al usuario antes de alertar. */
    ALERT,
}

/**
 * Frase de pánico configurable.
 *
 * Se modela como clase y no como lista de strings porque cada frase necesita su
 * propia severidad y su propia tolerancia a errores: "auxilio" admite menos
 * tolerancia que "ayuda", porque "ayuda" aparece en conversación normal
 * ("¿me ayudas con esto?") y disparar un SOS por eso destruiría la confianza
 * en la feature completa.
 */
data class PanicPhrase(
    val raw: String,
    val severity: PanicSeverity,
    val tokens: List<String>,
    /** Distancia de edición máxima permitida por token. */
    val maxEditDistancePerToken: Int,
    /**
     * Si es true, la frase sola basta para disparar. Si es false, se exige que
     * el reconocedor además reporte confianza alta, para reducir falsos positivos
     * en palabras cortas y ambiguas.
     */
    val requiresHighConfidence: Boolean,
)

/** Resultado de una detección positiva. */
data class PanicMatch(
    val phrase: PanicPhrase,
    /** Texto reconocido que produjo el match, para mostrar en la UI y auditar. */
    val matchedText: String,
    val totalEditDistance: Int,
    /** Confianza mínima reportada por el reconocedor para los tokens matcheados. */
    val confidence: Float?,
) {
    val isSos: Boolean get() = phrase.severity == PanicSeverity.SOS
}

/**
 * Matcher de palabras de pánico sobre la salida de un reconocedor de voz.
 *
 * ── El problema real ─────────────────────────────────────────────────────────
 * Un reconocedor offline de modelo pequeño NO transcribe "auxilio" de forma
 * fiable. En la práctica devuelve "ausilio", "aucilio", "hauxilio", "o silvio".
 * Un match exacto por substring — que es lo que se implementa primero en casi
 * todos los proyectos — falla en el momento exacto en que la feature debe
 * funcionar, y falla en silencio: el usuario grita y no pasa nada.
 *
 * Por eso este matcher es tolerante a errores, con tres defensas:
 *
 * **1. Normalización.** Minúsculas, sin tildes, sin puntuación. "¡AUXILIO!" y
 * "auxilio" deben ser lo mismo.
 *
 * **2. Distancia de edición acotada por longitud.** Se permite 1 error en
 * palabras de hasta 6 letras y 2 en palabras más largas. El umbral escala con
 * la longitud porque a más letras, más probabilidad de que el modelo se
 * equivoque en una.
 *
 * **3. Guarda de ambigüedad.** Palabras cortas y comunes ("ayuda", "socorro")
 * exigen confianza alta del reconocedor. Sin esta guarda, la app dispara SOS en
 * conversaciones normales y el usuario la desactiva para siempre.
 *
 * ── Por qué Kotlin puro ──────────────────────────────────────────────────────
 * Este es el componente más crítico de toda la app en términos de "si falla,
 * alguien puede lastimarse". Debe tener una suite de tests exhaustiva que corra
 * en JVM, incluyendo casos adversarios con errores de reconocimiento reales.
 * Ninguna dependencia de Android.
 */
class PanicPhraseMatcher(
    phrases: List<PanicPhrase>,
    /** Confianza mínima del reconocedor para frases marcadas `requiresHighConfidence`. */
    private val highConfidenceThreshold: Float = DEFAULT_HIGH_CONFIDENCE,
) {

    private val phrases: List<PanicPhrase> = phrases.sortedByDescending { it.tokens.size }

    /**
     * Busca una frase de pánico en [recognizedText].
     *
     * [confidences] es opcional: la lista paralela de confianzas por token que
     * entrega el reconocedor en su resultado final. Cuando es null, las frases
     * que exigen alta confianza se aceptan igual (comportamiento permisivo),
     * porque en un resultado parcial Vosk no entrega confianzas y preferimos
     * un falso positivo a perder un grito de auxilio.
     */
    fun find(recognizedText: String, confidences: List<Float>? = null): PanicMatch? {
        val tokens = tokenize(recognizedText)
        if (tokens.isEmpty()) return null

        for (phrase in phrases) {
            val match = matchPhrase(phrase, tokens, confidences, recognizedText)
            if (match != null) return match
        }
        return null
    }

    private fun matchPhrase(
        phrase: PanicPhrase,
        tokens: List<String>,
        confidences: List<Float>?,
        originalText: String,
    ): PanicMatch? {
        val phraseLength = phrase.tokens.size
        if (tokens.size < phraseLength) return null

        // Ventana deslizante: la frase puede aparecer en cualquier posición del
        // texto reconocido ("por favor auxilio ayudenme" debe matchear).
        for (start in 0..(tokens.size - phraseLength)) {
            val window = tokens.subList(start, start + phraseLength)
            var totalDistance = 0
            var matched = true
            var minConfidence: Float? = null

            for (index in window.indices) {
                val candidate = window[index]
                val target = phrase.tokens[index]
                val distance = levenshtein(candidate, target)

                if (distance > phrase.maxEditDistancePerToken) {
                    matched = false
                    break
                }

                totalDistance += distance
                if (confidences != null && start + index < confidences.size) {
                    val confidence = confidences[start + index]
                    minConfidence = if (minConfidence == null) confidence else min(minConfidence, confidence)
                }
            }

            if (!matched) continue

            // Guarda de ambigüedad: si el reconocedor no está seguro y la frase
            // es de las que se confunden con habla normal, no disparamos.
            if (phrase.requiresHighConfidence && minConfidence != null &&
                minConfidence < highConfidenceThreshold
            ) {
                continue
            }

            return PanicMatch(
                phrase = phrase,
                matchedText = originalText,
                totalEditDistance = totalDistance,
                confidence = minConfidence,
            )
        }
        return null
    }

    companion object {
        const val DEFAULT_HIGH_CONFIDENCE = 0.55f

        /**
         * Conjunto por defecto para español rioplatense/andino.
         *
         * Incluye las variantes dialectales y las deformaciones que el propio
         * reconocedor produce con más frecuencia. No es una lista arbitraria:
         * cada entrada existe porque o bien es una forma real de pedir ayuda en
         * español, o bien es un error de transcripción recurrente del modelo.
         */
        fun defaultSpanishPhrases(): List<PanicPhrase> = listOf(
            // SOS explícitos, sin ambigüedad: disparan directo.
            panicPhrase("auxilio", PanicSeverity.SOS, requiresHighConfidence = false),
            panicPhrase("socorro", PanicSeverity.SOS, requiresHighConfidence = false),
            panicPhrase("ayudenme", PanicSeverity.SOS, requiresHighConfidence = false),
            panicPhrase("por favor ayuda", PanicSeverity.SOS, requiresHighConfidence = false),

            // Cortos y ambiguos: exigen confianza alta del reconocedor.
            panicPhrase("ayuda", PanicSeverity.SOS, requiresHighConfidence = true),

            // Frases de contexto de asalto, no gritos. Elevan vigilancia.
            panicPhrase("me estan siguiendo", PanicSeverity.ALERT, requiresHighConfidence = false),
            panicPhrase("me quieren robar", PanicSeverity.ALERT, requiresHighConfidence = false),
            panicPhrase("llama a la policia", PanicSeverity.ALERT, requiresHighConfidence = false),
            panicPhrase("nomadsafe ayuda", PanicSeverity.SOS, requiresHighConfidence = false),
        )

        /** Construye una [PanicPhrase] derivando tolerancia y tokens de la longitud. */
        fun panicPhrase(
            raw: String,
            severity: PanicSeverity,
            requiresHighConfidence: Boolean,
        ): PanicPhrase {
            val tokens = tokenize(raw)
            val maxTokenLength = tokens.maxOfOrNull { it.length } ?: 0
            return PanicPhrase(
                raw = raw,
                severity = severity,
                tokens = tokens,
                // 1 error hasta 6 letras, 2 después. Ver KDoc de la clase.
                maxEditDistancePerToken = if (maxTokenLength <= 6) 1 else 2,
                requiresHighConfidence = requiresHighConfidence,
            )
        }

        /**
         * Normaliza y tokeniza.
         *
         * Se quitan tildes a mano en vez de usar `Normalizer` de java.text porque
         * ése es un API de escritorio que en Android requiere desugaring o API 26+,
         * y esta clase debe seguir siendo Kotlin puro para correr en JVM tests.
         */
        fun tokenize(text: String): List<String> = text
            .lowercase()
            .map { stripAccents(it) }
            .joinToString("")
            .split(SPLIT_REGEX)
            .filter { it.isNotEmpty() }

        private fun stripAccents(character: Char): Char = when (character) {
            'á', 'à', 'ä', 'â', 'ã' -> 'a'
            'é', 'è', 'ë', 'ê' -> 'e'
            'í', 'ì', 'ï', 'î' -> 'i'
            'ó', 'ò', 'ö', 'ô', 'õ' -> 'o'
            'ú', 'ù', 'ü', 'û' -> 'u'
            'ñ' -> 'n'
            'ç' -> 'c'
            '¡', '¿', '·' -> ' '
            else -> character
        }

        private val SPLIT_REGEX = Regex("[^a-z0-9]+")

        /**
         * Distancia de Levenshtein, con corte temprano.
         *
         * [maxDistance] permite abortar apenas se supera el umbral. En la práctica
         * casi todas las comparaciones terminan en la primera o segunda fila, lo
         * que importa porque esto corre en un bucle continuo dentro de un
         * foreground service y cada milisegundo es batería del usuario.
         */
        fun levenshtein(source: String, target: String, maxDistance: Int = Int.MAX_VALUE): Int {
            if (source == target) return 0

            val sourceLength = source.length
            val targetLength = target.length

            // Si las longitudes difieren más que el umbral, es imposible.
            if (kotlin.math.abs(sourceLength - targetLength) > maxDistance) return maxDistance + 1
            if (sourceLength == 0) return targetLength
            if (targetLength == 0) return sourceLength

            // Dos filas rodantes en vez de la matriz completa: O(min(m,n)) memoria.
            var previous = IntArray(targetLength + 1) { it }
            var current = IntArray(targetLength + 1)

            for (i in 1..sourceLength) {
                current[0] = i
                var rowMinimum = current[0]

                for (j in 1..targetLength) {
                    val substitutionCost = if (source[i - 1] == target[j - 1]) 0 else 1
                    current[j] = min(
                        min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + substitutionCost,
                    )
                    rowMinimum = min(rowMinimum, current[j])
                }

                // Corte temprano: si lo mejor de esta fila ya excede el umbral,
                // ninguna fila posterior puede mejorar (la distancia es monótona).
                if (rowMinimum > maxDistance) return maxDistance + 1

                val swap = previous
                previous = current
                current = swap
            }

            return previous[targetLength]
        }
    }
}

/** Utilidad para armar el vocabulario restringido que se le pasa a Vosk. */
fun List<PanicPhrase>.toGrammar(): Array<String> {
    val words = this
        .flatMap { it.tokens }
        .distinct()
        .sorted()
        .toMutableList()

    // "[unk]" es OBLIGATORIO en la gramática de Vosk/Kaldi: sin ese token el
    // reconocedor fuerza TODO el audio a encajar en el vocabulario, y el ruido
    // de la calle se transcribe como "auxilio". Es la causa nº1 de falsos
    // positivos en keyword spotting con gramática restringida.
    words += "[unk]"
    return words.toTypedArray()
}

/** Longitud máxima sugerida para una frase de pánico (tokens). */
const val MAX_PANIC_PHRASE_TOKENS = 4

/** Helper para validaciones de entrada. */
fun isValidPanicPhrase(raw: String): Boolean {
    val tokens = PanicPhraseMatcher.tokenize(raw)
    return tokens.isNotEmpty() &&
        tokens.size <= MAX_PANIC_PHRASE_TOKENS &&
        tokens.all { it.length >= 3 } &&
        tokens.maxOf { it.length } <= 20
}

/** Devuelve el umbral de tolerancia recomendado para una palabra. */
fun recommendedEditDistance(wordLength: Int): Int = max(1, min(2, wordLength / 6))
