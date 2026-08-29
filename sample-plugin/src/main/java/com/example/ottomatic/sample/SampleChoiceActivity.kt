package com.example.ottomatic.sample

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.example.ottomatic.plugin.PluginChoiceRequest
import com.example.ottomatic.plugin.finishWithChoice
import com.example.ottomatic.plugin.finishWithoutChoosing

/**
 * The plugin's own chooser — what `@PluginChoice(chooser = SCREEN)` opens.
 *
 * Ottomatic resolves this Activity against **this** package through `PackageManager`,
 * having found `com.example.ottomatic.action.PLUGIN_CHOICE` in the manifest, and starts it
 * by component. It hands over three strings and takes back one.
 *
 * ## Why this exists when the list chooser already did
 *
 * The list chooser is right for two workspaces and hopeless for four thousand cards. This
 * one has a search box, which is the whole argument in one control: a `List<OptionWire>`
 * is a flat unsearchable column, and a plugin that hit that ceiling had exactly one way
 * out before protocol 2 — a text field asking for an id, which is the failure
 * `@PluginChoice` exists to close.
 *
 * ## What it is not
 *
 * Not a way into Ottomatic. This Activity gets [PluginChoiceRequest]'s three strings and
 * nothing else: no facade, no host library, no variable, no place. It answers with an id.
 * The `Intent` it sets is read for one extra and then dropped — never started, never
 * granted from — so choosing to render your own screen buys you rendering and no reach.
 *
 * Note also what it needs from Ottomatic to be written: two constants and two extension
 * functions. No theme, no widget, no library.
 */
class SampleChoiceActivity : Activity() {

    private lateinit var shown: List<Pair<String, String>>
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = PluginChoiceRequest.from(intent)

        // Scoped exactly as the list choosers are: the cards offered are the chosen
        // board's. The scope arrives in `config` because the field declared
        // `scopedBy = ["board"]` — an undeclared sibling would not be sent, and the host
        // would not clear this field when it changed either.
        val all = CARDS[request.config["board"]].orEmpty()

        if (request.source != CARD_SOURCE || all.isEmpty()) {
            // Nothing to offer is not an error worth a dialog: back out and leave whatever
            // the field already held.
            finishWithoutChoosing()
            return
        }

        title = "Choose a card"
        shown = all
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, all.map { it.second })

        val search = EditText(this).apply {
            hint = "Search"
            addTextChangedListener(
                object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) = filter(all, s?.toString().orEmpty())
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                },
            )
        }
        val list = ListView(this).apply {
            adapter = this@SampleChoiceActivity.adapter
            setOnItemClickListener { _, _, position, _ -> finishWithChoice(shown[position].first) }
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = "Cards on this board" })
                addView(search)
                addView(list)
            },
        )
    }

    private fun filter(all: List<Pair<String, String>>, query: String) {
        shown = all.filter { it.second.contains(query, ignoreCase = true) }
        adapter.clear()
        adapter.addAll(shown.map { it.second })
        adapter.notifyDataSetChanged()
    }

    // Backing out chooses nothing, so the field keeps what it had — and that needs no
    // code at all. An Activity that has not called `setResult` finishes with
    // `RESULT_CANCELED`, which is precisely what [finishWithoutChoosing] sets, so the
    // back gesture already answers correctly. Overriding `onBackPressed` to say it again
    // would only opt this screen out of predictive back on API 33+, where the framework
    // stops calling that method at all.
}
