package com.xxado.javacraft.account;

import com.mojang.authlib.minecraft.UserApiService;
import com.xxado.javacraft.JavaCraftBootstrap;
import com.xxado.javacraft.account.mixin.MinecraftAuthAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.network.chat.Component;

import java.net.Proxy;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Device-code login rendered inside Minecraft. Microsoft credentials stay on Microsoft's page. */
public final class MicrosoftAccountScreen extends Screen {
    private final Screen parent;
    private Button signInButton;
    private CompletableFuture<JavaCraftBootstrap.AuthSession> signIn;
    private String status = "Sign in securely through Microsoft's official page.";
    private String verificationUri = "";
    private String userCode = "";
    private String signedInAs = "";

    public MicrosoftAccountScreen(Screen parent) {
        super(Component.literal("JavaCraft Microsoft account"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int center = width / 2;
        if (JavaCraftBootstrap.savedMicrosoftClientId().isBlank())
            status = "This JavaCraft build is missing its Microsoft app configuration.";
        signInButton = Button.builder(Component.literal("Sign in with Microsoft"), ignored -> startSignIn())
                .bounds(center - scaled(190), panelTop() + scaled(96), scaled(380), 24).build();
        Button backButton = Button.builder(Component.literal("Back"), ignored -> onClose())
                .bounds(center - scaled(190), panelTop() + scaled(126), scaled(380), 24).build();
        addRenderableWidget(signInButton);
        addRenderableWidget(backButton);
        setInitialFocus(signInButton);
    }

    private void startSignIn() {
        if (signIn != null && !signIn.isDone()) return;
        String id = JavaCraftBootstrap.savedMicrosoftClientId();
        if (!id.matches("(?i)[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
            status = "This JavaCraft build is missing its Microsoft app configuration.";
            return;
        }
        verificationUri = "";
        userCode = "";
        signedInAs = "";
        status = "Contacting Microsoft…";
        signInButton.active = false;
        signIn = JavaCraftBootstrap.microsoftSignInFromMinecraft(id, prompt -> {
            boolean browserOpened = JavaCraftBootstrap.openMicrosoftSignInPage(
                    prompt.verificationUriComplete());
            minecraft.execute(() -> {
                verificationUri = prompt.verificationUri();
                userCode = prompt.userCode();
                status = browserOpened
                        ? "Microsoft's sign-in page opened. Finish signing in there."
                        : "Open the Microsoft page shown below on a phone or computer.";
            });
        });
        signIn.whenComplete((session, error) -> minecraft.execute(() -> {
            if (error != null) {
                status = "Sign-in failed: " + safeMessage(error);
                signInButton.active = true;
                return;
            }
            try {
                activateMinecraftAccount(minecraft, session);
                signedInAs = session.name();
                status = "Microsoft account connected for this game session.";
            } catch (Exception activationError) {
                status = "Microsoft signed in, but Minecraft could not switch accounts: "
                        + safeMessage(activationError);
            }
            signInButton.active = true;
        }));
    }

    private static void activateMinecraftAccount(Minecraft client,
                                                 JavaCraftBootstrap.AuthSession session) throws Exception {
        String rawUuid = session.uuid().replace("-", "");
        if (!rawUuid.matches("(?i)[0-9a-f]{32}"))
            throw new IllegalStateException("Microsoft returned an invalid Minecraft profile ID.");
        String dashedUuid = rawUuid.substring(0, 8) + "-" + rawUuid.substring(8, 12) + "-"
                + rawUuid.substring(12, 16) + "-" + rawUuid.substring(16, 20) + "-" + rawUuid.substring(20);
        User user = new User(session.name(), UUID.fromString(dashedUuid), session.accessToken(),
                optional(session.xuid()), optional(session.clientId()));
        UserApiService userApi = createUserApiService(session.accessToken());

        MinecraftAuthAccessor access = (MinecraftAuthAccessor) (Object) client;
        access.javacraft$setUser(user);
        access.javacraft$setUserApiService(userApi);
        access.javacraft$setProfileKeyPairManager(ProfileKeyPairManager.create(
                userApi, user, client.gameDirectory.toPath()));
        access.javacraft$setUserPropertiesFuture(CompletableFuture.supplyAsync(() -> {
            try { return userApi.fetchProperties(); }
            catch (Exception error) { throw new CompletionException(error); }
        }));
        access.javacraft$setProfileFuture(CompletableFuture.supplyAsync(
                () -> client.services().sessionService().fetchProfile(user.getProfileId(), true)));
        client.getProfileKeyPairManager().prepareKeyPair();
    }

    /** Authlib moved this factory in 26.3; select the API shipped with the chosen release. */
    private static UserApiService createUserApiService(String accessToken) throws Exception {
        try {
            Class<?> discoveryType = Class.forName(
                    "com.mojang.authlib.services.MinecraftServicesDiscoveryService");
            Object discovery = discoveryType.getMethod("create", Proxy.class).invoke(null, Proxy.NO_PROXY);
            return (UserApiService) discoveryType.getMethod("createUserApiService", String.class)
                    .invoke(discovery, accessToken);
        } catch (ClassNotFoundException olderAuthlib) {
            Class<?> environmentType = Class.forName("com.mojang.authlib.Environment");
            Class<?> yggdrasilEnvironment = Class.forName(
                    "com.mojang.authlib.yggdrasil.YggdrasilEnvironment");
            Object productionMode = yggdrasilEnvironment.getMethod("valueOf", String.class)
                    .invoke(null, "PROD");
            Object production = yggdrasilEnvironment.getMethod("getEnvironment").invoke(productionMode);
            Class<?> yggdrasilType = Class.forName("com.mojang.authlib.yggdrasil.YggdrasilUserApiService");
            return (UserApiService) yggdrasilType
                    .getConstructor(String.class, Proxy.class, environmentType)
                    .newInstance(accessToken, Proxy.NO_PROXY, production);
        }
    }

    private static Optional<String> optional(String value) {
        return value == null || value.isBlank() || value.equals("0") ? Optional.empty() : Optional.of(value);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xd9000000);
        int center = width / 2;
        int top = panelTop();
        graphics.fill(center - scaled(240), top, center + scaled(240), top + scaled(430), 0xff202732);
        graphics.outline(center - scaled(240), top, scaled(480), scaled(430), 0xff8997aa);
        graphics.centeredText(font, "JAVACRAFT ACCOUNT", center, top + scaled(18), 0xffffffff);
        graphics.centeredText(font, "Microsoft sign-in", center, top + scaled(42), 0xffc7d0dc);
        graphics.centeredText(font, "Your password is entered only on Microsoft's page.", center,
                top + scaled(67), 0xffc7d0dc);
        graphics.textWithWordWrap(font, Component.literal(status), center - scaled(196),
                top + scaled(164), scaled(392), 0xffe7eaf0);
        if (!verificationUri.isEmpty()) {
            graphics.centeredText(font, "OPEN THIS LINK ON YOUR PHONE OR PC", center,
                    top + scaled(214), 0xffc7d0dc);
            graphics.centeredText(font, verificationUri, center, top + scaled(236), 0xff83c8ff);
            graphics.fill(center - scaled(172), top + scaled(250),
                    center + scaled(172), top + scaled(310), 0xff111820);
            graphics.outline(center - scaled(172), top + scaled(250), scaled(344), scaled(60), 0xff83c8ff);
            graphics.centeredText(font, "ENTER THIS CODE", center, top + scaled(264), 0xffc7d0dc);
            graphics.centeredText(font, userCode, center, top + scaled(292), 0xff9ce6a3);
            graphics.textWithWordWrap(font,
                    Component.literal("Enter this code on Microsoft's page, then complete any password or security check."),
                    center - scaled(196), top + scaled(326), scaled(392), 0xffc7d0dc);
        }
        if (!signedInAs.isEmpty())
            graphics.centeredText(font, "SIGNED IN: " + signedInAs, center,
                    top + scaled(388), 0xff9ce6a3);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private int panelTop() {
        return Math.max(12, (height - scaled(430)) / 2);
    }

    private int scaled(int value) {
        float scale = Math.min(1.0f, Math.min((height - 24.0f) / 430.0f,
                (width - 24.0f) / 480.0f));
        return Math.round(value * Math.max(0.1f, scale));
    }

    @Override
    public void onClose() {
        if (signIn != null && !signIn.isDone()) signIn.cancel(true);
        minecraft.setScreenAndShow(parent);
    }

    private static String safeMessage(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current.getCause() != null)
                && current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        if (message == null || message.isBlank()) return current.getClass().getSimpleName();
        return message.length() > 120 ? message.substring(0, 117) + "…" : message;
    }
}
