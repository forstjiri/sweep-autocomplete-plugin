package dev.sweep.assistant.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.autocomplete.edit.engine.NesModelConfig
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
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel

class SweepSettingsConfigurable(
    private val project: Project,
) : Configurable {
    private val settings = SweepSettings.getInstance()

    private var enableAutocompleteCheckBox: JCheckBox? = null
    private var acceptWordOnRightArrowCheckBox: JCheckBox? = null
    private var disableConflictingPluginsCheckBox: JCheckBox? = null
    private var debounceSlider: JSlider? = null
    private var debounceValueLabel: JLabel? = null
    private var localPortSpinner: JSpinner? = null
    private var modelComboBox: JComboBox<String>? = null
    private var llamaServerPathField: TextFieldWithBrowseButton? = null
    private var extraLlamaServerArgsField: JTextField? = null
    private var flashAttentionComboBox: JComboBox<String>? = null
    private var contextSizeSpinner: JSpinner? = null

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

        modelComboBox =
            JComboBox(NesModelConfig.MODELS.map { it.displayName }.toTypedArray()).apply {
                selectedItem =
                    NesModelConfig.getModel(settings.autocompleteLocalModel).displayName
            }

        llamaServerPathField =
            TextFieldWithBrowseButton().apply {
                text = settings.llamaServerPath
                addBrowseFolderListener(
                    "Select llama-server",
                    "Path to the llama-server binary (leave empty to auto-detect)",
                    project,
                    com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFileDescriptor()
                        .withTitle("Select llama-server"),
                )
            }

        extraLlamaServerArgsField =
            JTextField(settings.extraLlamaServerArgs).apply { toolTipText = "Appended verbatim to the launch command, e.g. --threads 8" }

        flashAttentionComboBox =
            JComboBox(arrayOf("on", "auto", "off")).apply {
                selectedItem = settings.flashAttentionMode
                toolTipText = "Flash attention speeds up prompt processing; try 'auto' or 'off' if the GPU driver misbehaves"
            }

        contextSizeSpinner =
            JSpinner(SpinnerNumberModel(settings.llamaContextSize, 0, 131072, 1024)).apply {
                toolTipText = "KV context size (-c). 0 uses the llama-server default"
            }

        val form =
            FormBuilder
                .createFormBuilder()
                .addComponent(enableAutocompleteCheckBox!!)
                .addComponent(acceptWordOnRightArrowCheckBox!!)
                .addComponent(disableConflictingPluginsCheckBox!!)
                .addLabeledComponent("Autocomplete model:", modelComboBox!!)
                .addComponent(
                    JLabel(
                        "The model GGUF is downloaded once from Hugging Face on first start. " +
                            "Inference runs locally through llama-server.",
                    ),
                )
                .addLabeledComponent("Autocomplete debounce:", debouncePanel)
                .addLabeledComponent("Local autocomplete server port:", localPortSpinner!!)
                .addLabeledComponent("llama-server path:", llamaServerPathField!!)
                .addLabeledComponent("Extra llama-server arguments:", extraLlamaServerArgsField!!)
                .addLabeledComponent("Flash attention:", flashAttentionComboBox!!)
                .addLabeledComponent("Server context size:", contextSizeSpinner!!)
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
            (debounceSlider?.value?.toLong() ?: settings.getDebounceThresholdMs()) != settings.getDebounceThresholdMs() ||
            (localPortSpinner?.value as? Int) != settings.autocompleteLocalPort ||
            selectedModelId() != settings.autocompleteLocalModel ||
            llamaServerPathField?.text?.trim() != settings.llamaServerPath ||
            extraLlamaServerArgsField?.text?.trim() != settings.extraLlamaServerArgs ||
            flashAttentionComboBox?.selectedItem != settings.flashAttentionMode ||
            (contextSizeSpinner?.value as? Int) != settings.llamaContextSize

    override fun apply() {
        val wasEnabled = settings.nextEditPredictionFlagOn
        val shouldRestartServer =
            selectedModelId() != settings.autocompleteLocalModel ||
                (localPortSpinner?.value as? Int) != settings.autocompleteLocalPort ||
                llamaServerPathField?.text?.trim() != settings.llamaServerPath ||
                extraLlamaServerArgsField?.text?.trim() != settings.extraLlamaServerArgs ||
                flashAttentionComboBox?.selectedItem != settings.flashAttentionMode ||
                (contextSizeSpinner?.value as? Int) != settings.llamaContextSize

        enableAutocompleteCheckBox?.isSelected?.let { settings.nextEditPredictionFlagOn = it }
        acceptWordOnRightArrowCheckBox?.isSelected?.let { settings.acceptWordOnRightArrow = it }
        disableConflictingPluginsCheckBox?.isSelected?.let { settings.disableConflictingPlugins = it }
        debounceSlider?.value?.toLong()?.let { settings.autocompleteDebounceMs = it }
        (localPortSpinner?.value as? Int)?.let { settings.autocompleteLocalPort = it }
        selectedModelId()?.let { settings.autocompleteLocalModel = it }
        llamaServerPathField?.text?.trim()?.let { settings.llamaServerPath = it }
        extraLlamaServerArgsField?.text?.trim()?.let { settings.extraLlamaServerArgs = it }
        (flashAttentionComboBox?.selectedItem as? String)?.let { settings.flashAttentionMode = it }
        (contextSizeSpinner?.value as? Int)?.let { settings.llamaContextSize = it }

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
        debounceSlider?.value = settings.getDebounceThresholdMs().toInt().coerceIn(10, 1000)
        debounceValueLabel?.text = "${debounceSlider?.value ?: 0} ms"
        localPortSpinner?.value = settings.autocompleteLocalPort
        modelComboBox?.selectedItem = NesModelConfig.getModel(settings.autocompleteLocalModel).displayName
        llamaServerPathField?.text = settings.llamaServerPath
        extraLlamaServerArgsField?.text = settings.extraLlamaServerArgs
        flashAttentionComboBox?.selectedItem = settings.flashAttentionMode
        contextSizeSpinner?.value = settings.llamaContextSize
    }

    private fun selectedModelId(): String? {
        val displayName = modelComboBox?.selectedItem as? String ?: return null
        return NesModelConfig.MODELS.firstOrNull { it.displayName == displayName }?.id
    }
}
