package org.waste.of.time.gui

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text
import org.waste.of.time.manager.MessageManager

class ResumeConfirmScreen(
    private val callback: (Int) -> Unit,
    private val worldName: String
) : Screen(Text.translatable("worldtools.gui.resume_confirm.title")) {
    // Callback: 0 = Cancel, 1 = Overwrite, 2 = Resume

    override fun init() {
        val centerX = width / 2
        val centerY = height / 2

        // Resume (Primary Action)
        addDrawableChild(
            ButtonWidget.builder(Text.translatable("worldtools.gui.resume_confirm.resume")) {
                callback(2)
            }
            .dimensions(centerX - 155, centerY + 20, 100, 20)
            .build()
        )

        // Overwrite (Destructive Action)
        addDrawableChild(
            ButtonWidget.builder(Text.translatable("worldtools.gui.resume_confirm.overwrite")) {
                callback(1)
            }
            .dimensions(centerX - 50, centerY + 20, 100, 20)
            .build()
        )

        // Cancel
        addDrawableChild(
            ButtonWidget.builder(Text.translatable("gui.cancel")) {
                callback(0)
            }
            .dimensions(centerX + 55, centerY + 20, 100, 20)
            .build()
        )
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        // Draw background
        this.renderBackground(context, mouseX, mouseY, delta)

        // Draw title
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 50, 0xFFFFFF)
        
        // Draw message
        val message = Text.translatable("worldtools.gui.resume_confirm.message", worldName)
        context.drawCenteredTextWithShadow(textRenderer, message, width / 2, height / 2 - 30, 0xAAAAAA)
    }
    
    // For older minecraft versions or different mappings, renderBackground might be different. 
    // Assuming 1.20+ mapping based on other files.
}
