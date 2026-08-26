package dev.sweep.assistant.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.services.LocalAutocompleteServerManager
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.JTextField

class SweepSettingsConfigurable(
    private val project: Project,
) : Configurable {
    private val settings = SweepSettings.getInstance()

    private var enableAutocompleteCheckBox: JCheckBox? = null
    private var acceptWordOnRightArrowCheckBox: JCheckBox? = null
    private var disableConflictingPluginsCheckBox: JCheckBox? = null
    private var nativeEngineCheckBox: JCheckBox? = null
    private var debounceSlider: JSlider? = null
    private var debounceValueLabel: JLabel? = null
    private var localPortSpinner: JSpinner? = null
    private var modelComboBox: JComboBox<String>? = null
    private var customModelRepoField: JTextField? = null
    private var customModelFilenameField: JTextField? = null

    private val modelOptions = arrayOf(
        SweepSettings.MODEL_05B,
        SweepSettings.MODEL_15B,
        SweepSettings.MODEL_CUSTOM,
    )

    override fun getDisplayName(): String = "Vulcan Sweep"

    override fun createComponent(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(16)

        enableAutocompleteCheckBox =
            JCheckBox("Enable inline autocomplete suggestions", settings.nextEditPredictionFlagOn)
        acceptWordOnRightArrowCheckBox =
            JCheckBox("Accept next word on Right Arrow", settings.acceptWordOnRightArrow)
        disableConflictingPluginsCheckBox =
            JCheckBox("Automatically disable conflicting autocomplete plugins", settings.disableConflictingPlugins)
        nativeEngineCheckBox =
            JCheckBox("Use native engine (llama-server direct)", settings.autocompleteLocalNativeEngine)
        val effectiveDebounce = settings.getDebounceThresholdMs().toInt().coerceIn(10, 1000)
        debounceSlider =
            JSlider(10, 1000, effectiveDebounce).apply {
                majorTickSpacing = 100
                paintTicks = true
            }
        debounceValueLabel = JLabel("${debounceSlider!!.value} ms")
        debounceSlider!!.addChangeListener { debounceValueLabel?.text = "${debounceSlider!!.value} ms" }

        val debouncePanel =
            JPanel(BorderLayout()).apply {
                add(debounceSlider, BorderLayout.CENTER)
                add(debounceValueLabel, BorderLayout.EAST)
            }

        localPortSpinner =
            JSpinner(SpinnerNumberModel(settings.autocompleteLocalPort, 1024, 65535, 1))

        modelComboBox = JComboBox(modelOptions).apply {
            selectedItem = settings.autocompleteModel
            addActionListener { updateModelFields() }
        }
        customModelRepoField = JTextField(settings.customModelRepo)
        customModelFilenameField = JTextField(settings.customModelFilename)
        updateModelFields()

        val form =
            FormBuilder
                .createFormBuilder()
                .addComponent(enableAutocompleteCheckBox!!)
                .addComponent(acceptWordOnRightArrowCheckBox!!)
                .addComponent(disableConflictingPluginsCheckBox!!)
                .addComponent(nativeEngineCheckBox!!)
                .addComponent(
                    JLabel(
                        "Native engine builds prompts in-process and calls llama-server directly (faster). " +
                            "Requires llama-server on PATH; otherwise the bundled Python server is used.",
                    ),
                )
                .addLabeledComponent("Autocomplete model:", modelComboBox!!)
                .addLabeledComponent("Custom model repository:", customModelRepoField!!)
                .addLabeledComponent("Custom GGUF filename:", customModelFilenameField!!)
                .addComponent(JLabel("Supported: Sweep 0.5B/1.5B GGUF or another compatible custom GGUF model. AWQ and safetensors are not supported."))
                .addLabeledComponent("Autocomplete debounce:", debouncePanel)
                .addLabeledComponent("Local autocomplete server port:", localPortSpinner!!)
                .addComponentFillVertically(JPanel(), 0)
                .panel
        form.border = BorderFactory.createEmptyBorder()

        panel.add(form, BorderLayout.NORTH)
        return panel
    }

    override fun isModified(): Boolean =
        enableAutocompleteCheckBox?.isSelected != settings.nextEditPredictionFlagOn ||
            acceptWordOnRightArrowCheckBox?.isSelected != settings.acceptWordOnRightArrow ||
            disableConflictingPluginsCheckBox?.isSelected != settings.disableConflictingPlugins ||
            nativeEngineCheckBox?.isSelected != settings.autocompleteLocalNativeEngine ||
            (debounceSlider?.value?.toLong() ?: settings.getDebounceThresholdMs()) != settings.getDebounceThresholdMs() ||
            (localPortSpinner?.value as? Int) != settings.autocompleteLocalPort ||
            modelComboBox?.selectedItem != settings.autocompleteModel ||
            customModelRepoField?.text != settings.customModelRepo ||
            customModelFilenameField?.text != settings.customModelFilename

    override fun apply() {
        val wasEnabled = settings.nextEditPredictionFlagOn
        val shouldRestartServer =
            modelComboBox?.selectedItem != settings.autocompleteModel ||
                customModelRepoField?.text != settings.customModelRepo ||
                customModelFilenameField?.text != settings.customModelFilename ||
                (localPortSpinner?.value as? Int) != settings.autocompleteLocalPort ||
                nativeEngineCheckBox?.isSelected != settings.autocompleteLocalNativeEngine

        enableAutocompleteCheckBox?.isSelected?.let { settings.nextEditPredictionFlagOn = it }
        acceptWordOnRightArrowCheckBox?.isSelected?.let { settings.acceptWordOnRightArrow = it }
        disableConflictingPluginsCheckBox?.isSelected?.let { settings.disableConflictingPlugins = it }
        nativeEngineCheckBox?.isSelected?.let { settings.autocompleteLocalNativeEngine = it }
        debounceSlider?.value?.toLong()?.let { settings.autocompleteDebounceMs = it }
        (localPortSpinner?.value as? Int)?.let { settings.autocompleteLocalPort = it }
        (modelComboBox?.selectedItem as? String)?.let { settings.autocompleteModel = it }
        customModelRepoField?.text?.let { settings.customModelRepo = it }
        customModelFilenameField?.text?.let { settings.customModelFilename = it }

        if (shouldRestartServer && settings.nextEditPredictionFlagOn) {
            LocalAutocompleteServerManager.getInstance().restartServerInTerminal(project)
        } else if (!wasEnabled && settings.nextEditPredictionFlagOn) {
            // Autocomplete was just enabled — start the terminal server if it is not running yet.
            ApplicationManager.getApplication().executeOnPooledThread {
                val manager = LocalAutocompleteServerManager.getInstance()
                if (!manager.isServerHealthy()) {
                    manager.startServerInTerminal(project)
                }
            }
        }
    }

    override fun reset() {
        enableAutocompleteCheckBox?.isSelected = settings.nextEditPredictionFlagOn
        acceptWordOnRightArrowCheckBox?.isSelected = settings.acceptWordOnRightArrow
        disableConflictingPluginsCheckBox?.isSelected = settings.disableConflictingPlugins
        nativeEngineCheckBox?.isSelected = settings.autocompleteLocalNativeEngine
        debounceSlider?.value = settings.getDebounceThresholdMs().toInt().coerceIn(10, 1000)
        debounceValueLabel?.text = "${debounceSlider?.value ?: 0} ms"
        localPortSpinner?.value = settings.autocompleteLocalPort
        modelComboBox?.selectedItem = settings.autocompleteModel
        customModelRepoField?.text = settings.customModelRepo
        customModelFilenameField?.text = settings.customModelFilename
        updateModelFields()
    }

    private fun updateModelFields() {
        val model = modelComboBox?.selectedItem as? String
        val isCustom = model == SweepSettings.MODEL_CUSTOM
        customModelRepoField?.isEnabled = isCustom
        customModelFilenameField?.isEnabled = isCustom
    }
}
