package dev.sweep.assistant.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class SweepSettingsConfigurable(
    @Suppress("UNUSED_PARAMETER") project: Project,
) : Configurable {
    private val settings = SweepSettings.getInstance()

    private var enableAutocompleteCheckBox: JCheckBox? = null
    private var acceptWordOnRightArrowCheckBox: JCheckBox? = null
    private var disableConflictingPluginsCheckBox: JCheckBox? = null
    private var developerModeCheckBox: JCheckBox? = null
    private var debounceSlider: JSlider? = null
    private var debounceValueLabel: JLabel? = null
    private var localPortSpinner: JSpinner? = null

    override fun getDisplayName(): String = "Sweep Autocomplete"

    override fun createComponent(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(16)

        enableAutocompleteCheckBox =
            JCheckBox("Enable inline autocomplete suggestions", settings.nextEditPredictionFlagOn)
        acceptWordOnRightArrowCheckBox =
            JCheckBox("Accept next word on Right Arrow", settings.acceptWordOnRightArrow)
        disableConflictingPluginsCheckBox =
            JCheckBox("Automatically disable conflicting autocomplete plugins", settings.disableConflictingPlugins)
        developerModeCheckBox =
            JCheckBox("Developer mode (verbose logging, extra menu items)", settings.developerModeOn)

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

        val form =
            FormBuilder
                .createFormBuilder()
                .addComponent(enableAutocompleteCheckBox!!)
                .addComponent(acceptWordOnRightArrowCheckBox!!)
                .addComponent(disableConflictingPluginsCheckBox!!)
                .addLabeledComponent("Autocomplete debounce:", debouncePanel)
                .addLabeledComponent("Local autocomplete server port:", localPortSpinner!!)
                .addComponent(developerModeCheckBox!!)
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
            developerModeCheckBox?.isSelected != settings.developerModeOn ||
            (debounceSlider?.value?.toLong() ?: settings.getDebounceThresholdMs()) != settings.getDebounceThresholdMs() ||
            (localPortSpinner?.value as? Int) != settings.autocompleteLocalPort

    override fun apply() {
        enableAutocompleteCheckBox?.isSelected?.let { settings.nextEditPredictionFlagOn = it }
        acceptWordOnRightArrowCheckBox?.isSelected?.let { settings.acceptWordOnRightArrow = it }
        disableConflictingPluginsCheckBox?.isSelected?.let { settings.disableConflictingPlugins = it }
        developerModeCheckBox?.isSelected?.let { settings.developerModeOn = it }
        debounceSlider?.value?.toLong()?.let { settings.autocompleteDebounceMs = it }
        (localPortSpinner?.value as? Int)?.let { settings.autocompleteLocalPort = it }
    }

    override fun reset() {
        enableAutocompleteCheckBox?.isSelected = settings.nextEditPredictionFlagOn
        acceptWordOnRightArrowCheckBox?.isSelected = settings.acceptWordOnRightArrow
        disableConflictingPluginsCheckBox?.isSelected = settings.disableConflictingPlugins
        developerModeCheckBox?.isSelected = settings.developerModeOn
        debounceSlider?.value = settings.getDebounceThresholdMs().toInt().coerceIn(10, 1000)
        debounceValueLabel?.text = "${debounceSlider?.value ?: 0} ms"
        localPortSpinner?.value = settings.autocompleteLocalPort
    }
}
