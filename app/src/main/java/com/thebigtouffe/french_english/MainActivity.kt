package com.thebigtouffe.french_english

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import java.io.FileOutputStream
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomnavigation.BottomNavigationView


class MainActivity : AppCompatActivity() {
    private lateinit var searchEditText: EditText
    private lateinit var clearButton: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var loadingTextView: TextView
    private lateinit var suggestionsRecyclerView: RecyclerView
    private lateinit var suggestionsAdapter: SuggestionsAdapter
    private lateinit var dbHelper: DictionaryDatabaseHelper
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var searchCard: View
    private lateinit var emptyStateTextView: TextView
    private lateinit var historyRecyclerView: RecyclerView
    private lateinit var favoritesRecyclerView: RecyclerView
    private lateinit var historyAdapter: WordListAdapter
    private lateinit var favoritesAdapter: WordListAdapter
    private lateinit var historyEmptyText: TextView
    private lateinit var favoritesEmptyText: TextView

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var searchJob: Job? = null
    private var isLoading = false

    // Track current tab
    private var currentTab = Tab.WORDS

    enum class Tab { WORDS, HISTORY, FAVORITES }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { _, insets ->
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val navHeight = bottomNav.height.takeIf { it > 0 } ?: dpToPx(56)

            val keyboardOpen = imeHeight > 0

            bottomNav.visibility = if (keyboardOpen) View.GONE else View.VISIBLE

            val params = searchCard.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            params.bottomMargin = if (keyboardOpen) {
                imeHeight + dpToPx(12)
            } else {
                navHeight + navBarHeight + dpToPx(12)
            }
            searchCard.layoutParams = params
            insets
        }

        val topAppBar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topAppBar)
        setSupportActionBar(topAppBar)

        // Initialize views
        searchEditText = findViewById(R.id.searchEditText)
        clearButton = findViewById(R.id.clearButton)
        progressBar = findViewById(R.id.progressBar)
        loadingProgressBar = findViewById(R.id.loadingProgressBar)
        loadingTextView = findViewById(R.id.loadingTextView)
        suggestionsRecyclerView = findViewById(R.id.suggestionsRecyclerView)
        bottomNav = findViewById(R.id.bottomNavigation)
        searchCard = findViewById(R.id.searchCard)
        emptyStateTextView = findViewById(R.id.emptyStateTextView)
        historyRecyclerView = findViewById(R.id.historyRecyclerView)
        favoritesRecyclerView = findViewById(R.id.favoritesRecyclerView)
        historyEmptyText = findViewById(R.id.historyEmptyText)
        favoritesEmptyText = findViewById(R.id.favoritesEmptyText)

        setupSuggestions()
        setupHistoryList()
        setupFavoritesList()
        setupDatabase()
        setupBottomNav()

        clearButton.setOnClickListener {
            searchEditText.text.clear()
            hideSuggestions()
        }

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                clearButton.visibility = if (s?.isNotEmpty() == true) View.VISIBLE else View.GONE
            }

            override fun afterTextChanged(s: Editable?) {
                if (isLoading) return
                val query = s.toString().trim()
                if (query.length >= 1) {
                    searchForSuggestions(query)
                } else {
                    hideSuggestions()
                }
            }
        })
    }

    private fun setupBottomNav() {
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_words -> {
                    switchTab(Tab.WORDS)
                    true
                }
                R.id.nav_history -> {
                    switchTab(Tab.HISTORY)
                    true
                }
                R.id.nav_favorites -> {
                    switchTab(Tab.FAVORITES)
                    true
                }
                else -> false
            }
        }
    }

    private fun switchTab(tab: Tab) {
        currentTab = tab

        // Hide all tab content first
        suggestionsRecyclerView.visibility = View.GONE
        historyRecyclerView.visibility = View.GONE
        favoritesRecyclerView.visibility = View.GONE
        historyEmptyText.visibility = View.GONE
        favoritesEmptyText.visibility = View.GONE
        emptyStateTextView.visibility = View.GONE
        searchCard.visibility = View.GONE

        when (tab) {
            Tab.WORDS -> {
                searchCard.visibility = View.VISIBLE
                // Re-show suggestions if there's a query
                val query = searchEditText.text.toString().trim()
                if (query.length >= 1 && suggestionsAdapter.itemCount > 0) {
                    suggestionsRecyclerView.visibility = View.VISIBLE
                } else {
                    emptyStateTextView.visibility = View.VISIBLE
                }
            }
            Tab.HISTORY -> {
                val history = StorageHelper.getHistory(this)
                historyAdapter.updateList(history)
                if (history.isEmpty()) {
                    historyEmptyText.visibility = View.VISIBLE
                } else {
                    historyRecyclerView.visibility = View.VISIBLE
                }
            }
            Tab.FAVORITES -> {
                val favorites = StorageHelper.getFavorites(this)
                favoritesAdapter.updateList(favorites)
                if (favorites.isEmpty()) {
                    favoritesEmptyText.visibility = View.VISIBLE
                } else {
                    favoritesRecyclerView.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun setupDatabase() {
        scope.launch {
            val dbPath = getDatabasePath("dictionary.db")

            if (dbPath.exists() && dbPath.length() > 0) {
                dbHelper = DictionaryDatabaseHelper(this@MainActivity)
                val stats = withContext(Dispatchers.IO) { dbHelper.getDatabaseStats() }
                return@launch
            }

            showLoading(true, "Loading dictionary...")

            try {
                withContext(Dispatchers.IO) {
                    val assetFiles = assets.list("") ?: emptyArray()
                    val dbFileName = assetFiles.find {
                        it.endsWith(".db") || it == "dictionary.db"
                    }

                    if (dbFileName == null) {
                        throw Exception("Database not found in assets.\n\nBuild it with:\npython build_dictionary_db.py dictionary.xml")
                    }

                    assets.open(dbFileName).use { input ->
                        FileOutputStream(dbPath).use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            var totalBytes = 0L

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                totalBytes += bytesRead

                                if (totalBytes % (1024 * 1024) == 0L) {
                                    withContext(Dispatchers.Main) {
                                        loadingTextView.text =
                                            "Loading...\n${totalBytes / (1024 * 1024)} MB"
                                    }
                                }
                            }
                        }
                    }
                }

                dbHelper = DictionaryDatabaseHelper(this@MainActivity)
                val stats = withContext(Dispatchers.IO) { dbHelper.getDatabaseStats() }

                showLoading(false)

                Toast.makeText(
                    this@MainActivity,
                    "Dictionary loaded: ${stats.entryCount} entries",
                    Toast.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {
                showLoading(false)

                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Error")
                    .setMessage(e.message)
                    .setPositiveButton("OK") { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    private fun showLoading(show: Boolean, message: String = "") {
        isLoading = show
        loadingProgressBar.visibility = if (show) View.VISIBLE else View.GONE
        loadingTextView.visibility = if (show) View.VISIBLE else View.GONE
        loadingTextView.text = message
        searchEditText.isEnabled = !show
        clearButton.isEnabled = !show
    }

    private fun setupSuggestions() {
        suggestionsAdapter = SuggestionsAdapter { word ->
            showEntry(word)
            hideKeyboard()
        }

        suggestionsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = suggestionsAdapter
        }
    }

    private fun setupHistoryList() {
        historyAdapter = WordListAdapter { word -> showEntry(word) }
        historyRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = historyAdapter
        }
    }

    private fun setupFavoritesList() {
        favoritesAdapter = WordListAdapter { word -> showEntry(word) }
        favoritesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = favoritesAdapter
        }
    }

    private fun searchForSuggestions(query: String) {
        searchJob?.cancel()

        searchJob = scope.launch {
            delay(200)

            try {
                val suggestions = withContext(Dispatchers.IO) {
                    dbHelper.searchByPrefix(query, 20)
                }

                if (suggestions.isNotEmpty()) {
                    suggestionsAdapter.updateSuggestions(suggestions)
                    showSuggestions()
                } else {
                    hideSuggestions()
                }
            } catch (e: CancellationException) {
                // Cancelled
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showSuggestions() {
        if (currentTab == Tab.WORDS) {
            suggestionsRecyclerView.visibility = View.VISIBLE
            emptyStateTextView.visibility = View.GONE
        }
    }

    private fun hideSuggestions() {
        suggestionsRecyclerView.visibility = View.GONE
        if (currentTab == Tab.WORDS) {
            emptyStateTextView.visibility = View.VISIBLE
        }
    }

    private fun showEntry(word: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                dbHelper.getEntry(word)
            }

            if (result != null) {
                // Record in history
                StorageHelper.addToHistory(this@MainActivity, word)

                val intent = Intent(this@MainActivity, EntryActivity::class.java).apply {
                    putExtra("WORD_TITLE", word)
                    putExtra("ENTRY_XML", result)
                }
                startActivity(intent)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh current tab in case favorites changed in EntryActivity
        switchTab(currentTab)
        // Re-select the correct bottom nav item
        when (currentTab) {
            Tab.WORDS -> bottomNav.selectedItemId = R.id.nav_words
            Tab.HISTORY -> bottomNav.selectedItemId = R.id.nav_history
            Tab.FAVORITES -> bottomNav.selectedItemId = R.id.nav_favorites
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchEditText.windowToken, 0)
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
