package com.xxado.javacraft.account.mixin;

import com.mojang.authlib.minecraft.UserApiService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.CompletableFuture;

@Mixin(Minecraft.class)
public interface MinecraftAuthAccessor {
    @Mutable @Accessor("user") void javacraft$setUser(User user);
    @Mutable @Accessor("userApiService") void javacraft$setUserApiService(UserApiService service);
    @Mutable @Accessor("userPropertiesFuture")
    void javacraft$setUserPropertiesFuture(CompletableFuture<UserApiService.UserProperties> future);
    @Mutable @Accessor("profileKeyPairManager")
    void javacraft$setProfileKeyPairManager(ProfileKeyPairManager manager);
    // Authlib's ProfileResult package changed between Minecraft 26.2 and 26.3.
    @Mutable @Accessor("profileFuture") void javacraft$setProfileFuture(CompletableFuture<?> future);
}
