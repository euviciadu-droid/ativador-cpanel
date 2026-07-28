package app.tvdigital.ativador

import android.app.UiModeManager
import android.content.Context

import android.content.res.Configuration
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/**
 * Utilidades para o modo Android TV / TV Box.
 * Detecta TV por múltiplos sinais (UiMode, LEANBACK, TELEVISION, ausência de
 * touchscreen) e bloqueia o teclado virtual para usar só o controle remoto.
 */
object TvMode {

    fun isTv(ctx: Context): Boolean {
        return try {
            // Só ativa modo TV quando o sistema declara explicitamente
            // UI_MODE_TYPE_TELEVISION. Projetores, mini PCs e algumas TV Box
            // reportam FEATURE_LEANBACK mesmo tendo teclado/mouse/touch, o
            // que fazia o app bloquear o teclado virtual indevidamente.
            val uiMode = (ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
                ?.currentModeType ?: Configuration.UI_MODE_TYPE_UNDEFINED
            uiMode == Configuration.UI_MODE_TYPE_TELEVISION
        } catch (_: Exception) { false }
    }

    /** Prepara o EditText para operar sem teclado virtual em modo TV. */
    fun setupCodeInputForTv(input: EditText) {
        val ctx = input.context
        // Bloqueia totalmente o IME — usa só D-pad/numéricas do controle
        input.showSoftInputOnFocus = false
        input.inputType = InputType.TYPE_NULL
        input.setRawInputType(InputType.TYPE_NULL)
        input.setTextIsSelectable(false)
        input.keyListener = null
        input.isLongClickable = false
        input.isCursorVisible = true
        input.isFocusable = true
        input.isFocusableInTouchMode = true

        val hideIme = {
            try {
                val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(input.windowToken, 0)
            } catch (_: Exception) { /* noop */ }
        }
        input.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) hideIme() }
        input.setOnClickListener { hideIme() }

        input.requestFocus()
        input.post { hideIme() }
    }

    /**
     * Handler global de teclas para modo TV. Chame de `Activity.onKeyDown`.
     * Retorna true se consumiu a tecla.
     *
     * @param input  o EditText do código (6 dígitos)
     * @param onSubmit chamado quando o usuário confirma com OK/Enter/Ir e 6 dígitos
     */
    fun handleTvKey(
        keyCode: Int,
        event: KeyEvent,
        input: EditText,
        onSubmit: () -> Unit,
    ): Boolean {
        val current = input.text?.toString().orEmpty()
        val cursor = input.selectionStart.takeIf { it >= 0 } ?: current.length

        fun setCode(next: String, nextCursor: Int) {
            val clean = next.filter { it.isDigit() }.take(6)
            input.setText(clean)
            input.setSelection(nextCursor.coerceIn(0, clean.length))
        }

        fun replaceOrInsert(digit: String) {
            if (current.length < 6) {
                val pos = cursor.coerceIn(0, current.length)
                val next = current.substring(0, pos) + digit + current.substring(pos)
                setCode(next, pos + 1)
            } else {
                val pos = cursor.coerceIn(0, 5)
                val next = current.substring(0, pos) + digit + current.substring(pos + 1)
                setCode(next, (pos + 1).coerceAtMost(6))
            }
        }

        fun changeDigit(delta: Int) {
            if (current.isEmpty()) {
                setCode(if (delta >= 0) "0" else "9", 1)
                return
            }
            val pos = if (cursor >= current.length) current.length - 1 else cursor.coerceAtLeast(0)
            val value = current[pos].digitToIntOrNull() ?: 0
            val bumped = (value + delta + 10) % 10
            val next = current.substring(0, pos) + bumped.toString() + current.substring(pos + 1)
            setCode(next, pos + 1)
        }

        // Teclas numéricas 0-9 (funciona no controle com numpad)
        val digit = when (keyCode) {
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> "0"
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> "1"
            KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> "2"
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> "3"
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> "4"
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> "5"
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> "6"
            KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> "7"
            KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> "8"
            KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> "9"
            else -> null
        }
        if (digit != null) {
            replaceOrInsert(digit)
            return true
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                changeDigit(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                changeDigit(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (cursor < current.length) {
                    setCode(current, cursor + 1)
                } else if (current.length < 6) {
                    setCode(current + "0", current.length + 1)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (cursor > 0) setCode(current, cursor - 1)
                return true
            }
            KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                val pos = cursor.coerceIn(0, current.length)
                if (current.isNotEmpty() && pos > 0) {
                    val next = current.removeRange(pos - 1, pos)
                    setCode(next, pos - 1)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (current.length == 6) {
                    onSubmit()
                    return true
                }
                return false
            }
        }
        return false
    }
}
