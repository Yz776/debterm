package com.debterm.app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var prootManager: ProotManager
    private lateinit var webView: WebView
    private lateinit var progressLayout: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar

    private var masterFd: Int = -1
    private var childPid: Int = -1
    private var ptyIn: InputStream? = null
    private var ptyOut: OutputStream? = null

    private var ctrlActive = false
    private var altActive = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prootManager = ProotManager(this)

        webView = findViewById(R.id.terminal_webview)
        progressLayout = findViewById(R.id.progress_layout)
        progressText = findViewById(R.id.progress_status)
        progressBar = findViewById(R.id.progress_bar)

        setupExtraKeys()
        setupWebView()

        // Start background service
        val serviceIntent = Intent(this, TerminalService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        checkAndStartBootstrap()
    }

    private fun checkAndStartBootstrap() {
        if (!prootManager.isInstalled()) {
            progressLayout.visibility = View.VISIBLE
            webView.visibility = View.GONE

            lifecycleScope.launch {
                val success = prootManager.installDebian { status ->
                    runOnUiThread { progressText.text = status }
                }
                if (success) {
                    progressLayout.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                    startProotSession()
                } else {
                    progressText.text = "Error installing Debian rootfs. Check internet connection."
                }
            }
        } else {
            progressLayout.visibility = View.GONE
            webView.visibility = View.VISIBLE
            startProotSession()
        }
    }

    private fun startProotSession() {
        lifecycleScope.launch(Dispatchers.IO) {
            val (cmd, args) = prootManager.buildProotCommand()
            val envVars = prootManager.getProotEnvironment()

            val ptyInfo = PtyBridge.createSubprocess(
                cmd = cmd,
                args = args,
                envVars = envVars,
                cwd = prootManager.filesDir.absolutePath,
                rows = 30,
                cols = 100
            )

            if (ptyInfo != null && ptyInfo.size >= 2) {
                childPid = ptyInfo[0]
                masterFd = ptyInfo[1]

                val fdObj = PtyBridge.createJavaFileDescriptor(masterFd)
                ptyIn = FileInputStream(fdObj)
                ptyOut = FileOutputStream(fdObj)

                // Background thread reading from PTY master and sending to Terminal UI
                launch(Dispatchers.IO) {
                    val buffer = ByteArray(4096)
                    while (masterFd >= 0) {
                        try {
                            val count = ptyIn?.read(buffer) ?: -1
                            if (count <= 0) break
                            val text = String(buffer, 0, count, Charsets.UTF_8)
                            withContext(Dispatchers.Main) {
                                sendToTerminal(text)
                            }
                        } catch (e: Exception) {
                            break
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onTerminalInput(data: String) {
                sendInputToPty(data)
            }

            @JavascriptInterface
            fun onResize(cols: Int, rows: Int) {
                if (masterFd >= 0) {
                    PtyBridge.setWindowSize(masterFd, rows, cols)
                }
            }
        }, "DebTermBridge")

        // Load built-in terminal HTML
        val terminalHtml = generateEmbeddedTerminalHtml()
        webView.loadDataWithBaseURL("https://debterm.local", terminalHtml, "text/html", "UTF-8", null)
    }

    private fun sendToTerminal(data: String) {
        val escaped = data.replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("$", "\\$")
        webView.evaluateJavascript("window.writeTerminal(`$escaped`);", null)
    }

    private fun sendInputToPty(data: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ptyOut?.write(data.toByteArray(Charsets.UTF_8))
                ptyOut?.flush()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun setupExtraKeys() {
        val btnCtrl = findViewById<Button>(R.id.btn_ctrl)
        val btnAlt = findViewById<Button>(R.id.btn_alt)

        btnCtrl.setOnClickListener {
            ctrlActive = !ctrlActive
            btnCtrl.isSelected = ctrlActive
        }

        btnAlt.setOnClickListener {
            altActive = !altActive
            btnAlt.isSelected = altActive
        }

        findViewById<Button>(R.id.btn_esc).setOnClickListener { sendInputToPty("\u001b") }
        findViewById<Button>(R.id.btn_tab).setOnClickListener { sendInputToPty("\t") }
        findViewById<Button>(R.id.btn_dash).setOnClickListener { sendInputToPty("-") }
        findViewById<Button>(R.id.btn_slash).setOnClickListener { sendInputToPty("/") }
        findViewById<Button>(R.id.btn_pipe).setOnClickListener { sendInputToPty("|") }
        findViewById<Button>(R.id.btn_up).setOnClickListener { sendInputToPty("\u001b[A") }
        findViewById<Button>(R.id.btn_down).setOnClickListener { sendInputToPty("\u001b[B") }
        findViewById<Button>(R.id.btn_left).setOnClickListener { sendInputToPty("\u001b[D") }
        findViewById<Button>(R.id.btn_right).setOnClickListener { sendInputToPty("\u001b[C") }
    }

    private fun generateEmbeddedTerminalHtml(): String {
        return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/xterm@5.3.0/css/xterm.min.css">
            <style>
                body { margin:0; padding:0; background:#0a0d12; overflow:hidden; }
                #terminal { width:100vw; height:100vh; }
            </style>
        </head>
        <body>
            <div id="terminal"></div>
            <script src="https://cdn.jsdelivr.net/npm/xterm@5.3.0/lib/xterm.min.js"></script>
            <script src="https://cdn.jsdelivr.net/npm/xterm-addon-fit@0.8.0/lib/xterm-addon-fit.min.js"></script>
            <script>
                const term = new Terminal({
                    cursorBlink: true,
                    fontSize: 14,
                    fontFamily: 'monospace',
                    theme: { background: '#0a0d12', foreground: '#e6edf3' }
                });
                const fitAddon = new FitAddon.FitAddon();
                term.loadAddon(fitAddon);
                term.open(document.getElementById('terminal'));
                fitAddon.fit();

                term.onData(data => {
                    if (window.DebTermBridge) {
                        window.DebTermBridge.onTerminalInput(data);
                    }
                });

                window.writeTerminal = function(str) {
                    term.write(str);
                };

                window.addEventListener('resize', () => {
                    fitAddon.fit();
                    if (window.DebTermBridge) {
                        window.DebTermBridge.onResize(term.cols, term.rows);
                    }
                });
            </script>
        </body>
        </html>
        """.trimIndent()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (masterFd >= 0) {
            PtyBridge.closeFd(masterFd)
            masterFd = -1
        }
    }
}
