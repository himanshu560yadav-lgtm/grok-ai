package com.panda.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.panda.ai.databinding.ActivityMainBinding
import com.panda.ai.utilities.ApiKeyManager
import com.panda.ai.utilities.MemoryStore
import com.panda.ai.v2.AgentService
import com.panda.ai.v2.llm.LlmProvider
import com.panda.ai.v2.llm.ModelInfo
import com.panda.ai.v2.llm.ProviderDetector
import com.panda.ai.v2.llm.UniversalApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isListening = false
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val universalApi by lazy { UniversalApi(this) }
    private val modelButtons = mutableMapOf<String, Button>()

    // Speech recognition launcher
    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!matches.isNullOrEmpty()) {
                binding.etTask.setText(matches[0])
                // Auto start agent after voice input
                startAgent()
            }
        }
        isListening = false
        updateMicButton()
    }

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Toast.makeText(this, "Permissions granted!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Some permissions denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        checkPermissions()
        checkAccessibilityService()
    }

    private fun setupUI() {
        // Load saved API key + re-fetch its model list so the previous
        // selection still shows as selected.
        val savedKey = ApiKeyManager.getApiKey(this)
        if (savedKey.isNotEmpty()) {
            binding.etApiKey.setText(savedKey)
            val provider = ProviderDetector.detect(savedKey)
            if (provider != null) {
                loadModels(savedKey, provider, silent = true)
            }
        }

        binding.btnLoadModels.setOnClickListener {
            val key = binding.etApiKey.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "API key cannot be empty!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val provider = ProviderDetector.detect(key)
            if (provider == null) {
                binding.tvProviderStatus.text = "❌ Key samajh nahi aayi. Sirf Gemini (AIza...) ya NVIDIA NIM (nvapi-...) chalega."
                return@setOnClickListener
            }

            ApiKeyManager.saveApiKey(this, key)
            loadModels(key, provider, silent = false)
        }

        // Start button
        binding.btnStart.setOnClickListener {
            startAgent()
        }

        // Stop button
        binding.btnStop.setOnClickListener {
            stopAgent()
        }

        // Mic button
        binding.btnMic.setOnClickListener {
            if (isListening) {
                isListening = false
                updateMicButton()
            } else {
                startVoiceInput()
            }
        }

        // Update UI based on agent state
        updateAgentUI()
    }

    private fun loadModels(apiKey: String, provider: LlmProvider, silent: Boolean) {
        binding.tvProviderStatus.text = "🔎 ${provider.displayName} detect hua, models load ho rahe hain..."
        binding.btnLoadModels.isEnabled = false
        binding.modelListContainer.removeAllViews()
        modelButtons.clear()

        activityScope.launch {
            try {
                val models = universalApi.fetchModels(apiKey, provider)
                if (models.isEmpty()) {
                    binding.tvProviderStatus.text = "⚠️ ${provider.displayName} se koi usable model nahi mila."
                } else {
                    binding.tvProviderStatus.text = "✅ ${provider.displayName} — ${models.size} model mile, ek chun le:"
                    renderModelButtons(models)
                }
            } catch (e: Exception) {
                binding.tvProviderStatus.text = "❌ Models load nahi hue: ${e.message}"
                if (!silent) {
                    Toast.makeText(this@MainActivity, "Model list fetch fail ho gaya, key check kar le", Toast.LENGTH_LONG).show()
                }
            } finally {
                binding.btnLoadModels.isEnabled = true
            }
        }
    }

    private fun renderModelButtons(models: List<ModelInfo>) {
        val currentSelection = ApiKeyManager.getSelectedModel(this)
        binding.modelListContainer.removeAllViews()
        modelButtons.clear()

        for (model in models) {
            val button = Button(this).apply {
                text = if (model.isFree) "✅ ${model.id}  (Free)" else "💰 ${model.id}  (Paid)"
                isAllCaps = false
                gravity = Gravity.START
                textSize = 13f
                setPadding(24, 20, 24, 20)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 8 }
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (model.id == currentSelection) Color.parseColor("#006400")
                    else if (model.isFree) Color.parseColor("#1A1A2E")
                    else Color.parseColor("#3A2A1A")
                )
                setOnClickListener {
                    ApiKeyManager.saveSelectedModel(this@MainActivity, model.id)
                    highlightSelected(model.id, models)
                    Toast.makeText(this@MainActivity, "Selected: ${model.id}", Toast.LENGTH_SHORT).show()
                }
            }
            modelButtons[model.id] = button
            binding.modelListContainer.addView(button)
        }
    }

    private fun highlightSelected(selectedId: String, models: List<ModelInfo>) {
        for (model in models) {
            val button = modelButtons[model.id] ?: continue
            val color = when {
                model.id == selectedId -> Color.parseColor("#006400")
                model.isFree -> Color.parseColor("#1A1A2E")
                else -> Color.parseColor("#3A2A1A")
            }
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        }
    }

    private fun startAgent() {
        val apiKey = ApiKeyManager.getApiKey(this)
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Pehle API key paste karke 'Free Models Load Karo' dabao!", Toast.LENGTH_LONG).show()
            return
        }

        if (!ApiKeyManager.hasSelectedModel(this)) {
            Toast.makeText(this, "Pehle ek model select karo list mein se!", Toast.LENGTH_LONG).show()
            return
        }

        val task = binding.etTask.text.toString().trim()
        if (task.isEmpty()) {
            Toast.makeText(this, "Please enter a task!", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Please enable Accessibility Service first!", Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please grant overlay permission!", Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }

        // Save task to memory
        MemoryStore.saveLastTask(this, task)

        // Start agent service
        AgentService.start(this, task)

        binding.etTask.setText("")
        Toast.makeText(this, "Agent started!", Toast.LENGTH_SHORT).show()
        updateAgentUI()
    }

    private fun stopAgent() {
        AgentService.stop(this)
        Toast.makeText(this, "Agent stopped!", Toast.LENGTH_SHORT).show()
        updateAgentUI()
    }

    private fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Bol do task...")
        }

        try {
            isListening = true
            updateMicButton()
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            isListening = false
            updateMicButton()
            Toast.makeText(this, "Voice input not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateMicButton() {
        if (isListening) {
            binding.btnMic.text = "🔴 Sun raha hun..."
        } else {
            binding.btnMic.text = "🎤 Bolo"
        }
    }

    private fun updateAgentUI() {
        val isRunning = AgentService.isRunning
        binding.btnStart.isEnabled = !isRunning
        binding.btnStop.isEnabled = isRunning
        binding.tvStatus.text = if (isRunning) "✅ Agent Running..." else "⏹ Agent Stopped"
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun checkAccessibilityService() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(
                this,
                "Accessibility Service enable karo settings mein!",
                Toast.LENGTH_LONG
            ).show()
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Overlay permission do settings mein!",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "${packageName}/${ScreenInteractionService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(service)
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        updateAgentUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }
}
