package pl.necek.wywoz

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

class SetupActivity : Activity() {
    private lateinit var store: Store
    private lateinit var streetInput: AutoCompleteTextView
    private lateinit var numberSpinner: Spinner
    private lateinit var status: TextView
    private lateinit var saveButton: Button
    private var streets: Map<String, List<String>> = emptyMap()
    private var numbers: List<ApiItem> = emptyList()
    private var selectedStreet: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)

        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        form.addView(label("Adres odbioru odpadów", 30f, TEXT, bold = true))
        form.addView(label("Wybierz ulicę i numer tak jak w wyszukiwarce MPO Kraków.", 18f, MUTED).apply {
            setPadding(0, dp(8), 0, dp(24))
        })

        streetInput = AutoCompleteTextView(this).apply {
            hint = "Ulica"
            textSize = 22f
            threshold = 1
            setSingleLine(true)
            isEnabled = false
        }
        streetInput.setOnItemClickListener { parent, _, position, _ ->
            selectStreet(parent.getItemAtPosition(position) as String)
        }
        form.addView(streetInput, LinearLayout.LayoutParams(MATCH, WRAP))

        form.addView(label("Numer", 16f, MUTED).apply { setPadding(0, dp(20), 0, dp(4)) })
        numberSpinner = Spinner(this).apply { isEnabled = false }
        form.addView(numberSpinner, LinearLayout.LayoutParams(MATCH, dp(56)))

        status = label("", 16f, MUTED).apply { setPadding(0, dp(20), 0, dp(20)) }
        form.addView(status)

        saveButton = Button(this).apply {
            text = "Zapisz"
            textSize = 20f
            isEnabled = false
        }
        saveButton.setOnClickListener { save() }
        form.addView(saveButton, LinearLayout.LayoutParams(MATCH, WRAP))

        if (store.numberId != null) {
            form.addView(label("Obecnie: ${store.address}", 16f, MUTED).apply { setPadding(0, dp(20), 0, 0) })
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(form)
        }
        scroll.applySystemInsets(dp(32))
        setContentView(scroll)
        loadStreets()
    }

    private fun loadStreets() {
        status.text = "Pobieram listę ulic…"
        status.setOnClickListener(null)
        Thread {
            val result = runCatching { MpoApi.streets() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { list ->
                    streets = list
                    streetInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, list.keys.toList()))
                    streetInput.isEnabled = true
                    status.text = "Wpisz początek nazwy ulicy i wybierz ją z listy"
                    val current = store.street
                    if (current != null && current in list) {
                        streetInput.setText(current, false)
                        selectStreet(current)
                    } else {
                        streetInput.requestFocus()
                    }
                }.onFailure { e ->
                    status.text = "Nie udało się pobrać listy ulic: ${e.message}\nDotknij tutaj, aby spróbować ponownie"
                    status.setOnClickListener { loadStreets() }
                }
            }
        }.start()
    }

    private fun selectStreet(name: String) {
        selectedStreet = name
        numbers = emptyList()
        numberSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, emptyList<String>())
        numberSpinner.isEnabled = false
        saveButton.isEnabled = false
        status.text = "Pobieram numery dla: $name…"
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(streetInput.windowToken, 0)
        val ids = streets[name].orEmpty()
        Thread {
            val result = runCatching { MpoApi.numbers(ids) }
            runOnUiThread {
                if (isDestroyed || selectedStreet != name) return@runOnUiThread
                result.onSuccess { list ->
                    numbers = list
                    if (list.isEmpty()) {
                        status.text = "Brak numerów dla tej ulicy"
                        return@onSuccess
                    }
                    numberSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, list.map { it.name }).also {
                        it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    }
                    val current = if (name == store.street) list.indexOfFirst { it.id == store.numberId } else -1
                    if (current >= 0) numberSpinner.setSelection(current)
                    numberSpinner.isEnabled = true
                    saveButton.isEnabled = true
                    status.text = "Wybierz numer i zapisz"
                }.onFailure { e ->
                    status.text = "Nie udało się pobrać numerów: ${e.message}"
                }
            }
        }.start()
    }

    private fun save() {
        val street = selectedStreet ?: return
        val item = numbers.getOrNull(numberSpinner.selectedItemPosition) ?: return
        store.setAddress(street, item.name, item.id)
        finish()
    }
}
