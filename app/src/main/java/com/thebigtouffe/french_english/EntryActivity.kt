package com.thebigtouffe.french_english

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar


class EntryActivity : AppCompatActivity() {

    private var word: String? = null
    private var isFavorite = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_entry)

        word = intent.getStringExtra("WORD_TITLE")
        val xml = intent.getStringExtra("ENTRY_XML") ?: ""

        val toolbar = findViewById<MaterialToolbar>(R.id.entryToolbar)
        toolbar.title = word
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        // Check current favorite status
        isFavorite = word?.let { StorageHelper.isFavorite(this, it) } ?: false

        val webView = findViewById<WebView>(R.id.entryWebView)
        webView.settings.allowFileAccess = true
        webView.settings.allowFileAccessFromFileURLs = true
        webView.settings.allowUniversalAccessFromFileURLs = true
        webView.loadDataWithBaseURL(
            "file:///android_asset/",
            buildHtmlPage(xml),
            "text/html",
            "UTF-8",
            null
        )
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.entry_menu, menu)
        updateStarIcon(menu.findItem(R.id.action_favorite))
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_favorite -> {
                word?.let { w ->
                    if (isFavorite) {
                        StorageHelper.removeFavorite(this, w)
                        isFavorite = false
                    } else {
                        StorageHelper.addFavorite(this, w)
                        isFavorite = true
                    }
                    updateStarIcon(item)
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun updateStarIcon(item: MenuItem?) {
        item?.setIcon(
            if (isFavorite) android.R.drawable.btn_star_big_on
            else android.R.drawable.btn_star_big_off
        )
    }

    private fun buildHtmlPage(entryXml: String): String {
        return """
        <!DOCTYPE html>
        <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <link rel="stylesheet" href="file:///android_asset/style.css">
            </head>
            <body>
                $entryXml
            </body>
        </html>
        """.trimIndent()
    }
}
