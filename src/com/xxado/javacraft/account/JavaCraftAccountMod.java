package com.xxado.javacraft.account;

import com.xxado.javacraft.JavaCraftBootstrap;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.lang.reflect.Method;

/** Adds a Microsoft account entry point to Minecraft's own title screen. */
public final class JavaCraftAccountMod implements ClientModInitializer {
    private static final Method ADD_WIDGET = findAddWidget();
    private static final Method REMOVE_WIDGET = findRemoveWidget();

    @Override
    public void onInitializeClient() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof TitleScreen)) return;
            for (GuiEventListener child : screen.children()) {
                if (!(child instanceof Button quit) || !isQuitButton(quit)) continue;
                addAccountButton(client, screen, quit.getX(), quit.getY(), quit.getWidth(), quit.getHeight());
                try {
                    REMOVE_WIDGET.invoke(screen, quit);
                } catch (ReflectiveOperationException error) {
                    throw new IllegalStateException("Could not replace Minecraft's quit button", error);
                }
                return;
            }
            // Keep the account entry accessible if a future title-screen layout
            // changes its quit-button component or removes it entirely.
            addAccountButton(client, screen, width - 180, height - 30, 170, 20);
        });
    }

    private static boolean isQuitButton(Button button) {
        return button.getMessage().getContents() instanceof TranslatableContents translated
                && "menu.quit".equals(translated.getKey());
    }

    private static void addAccountButton(Minecraft client, Screen parent,
                                         int x, int y, int width, int height) {
        Button account = Button.builder(Component.literal("JavaCraft Account"),
                        ignored -> client.setScreenAndShow(new MicrosoftAccountScreen(parent)))
                .bounds(x, y, width, height)
                .build();
        try {
            ADD_WIDGET.invoke(parent, account);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not add JavaCraft account button", error);
        }
    }

    private static Method findAddWidget() {
        try {
            // The generic method erases to GuiEventListener in Minecraft 26.3.
            Method method = Screen.class.getDeclaredMethod("addRenderableWidget", GuiEventListener.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static Method findRemoveWidget() {
        try {
            Method method = Screen.class.getDeclaredMethod("removeWidget", GuiEventListener.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}
