{
  description = "ScalarEvolution NeoForge dev shell — JDK 21 and LWJGL runtime libraries for runClient/runGameTestServer.";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { nixpkgs, flake-utils, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
        jdk = pkgs.jdk21;

        # Shared libraries that Minecraft's bundled LWJGL dlopens at runtime.
        # Must be visible via LD_LIBRARY_PATH because nix-built Java binaries
        # do not inherit FHS search paths.
        lwjglRuntimeLibs = with pkgs; [
          # GL / GLX / EGL dispatch.
          libglvnd
          # X11 client libraries for GLFW's X11 backend.
          libx11
          libxext
          libxrandr
          libxcursor
          libxxf86vm
          libxi
          libxinerama
          # Input / keymap.
          libxkbcommon
          # Audio (covers both ALSA-backed and PulseAudio-backed MC builds).
          libpulseaudio
          alsa-lib
          openal
          # Misc system libs MC / its loaders touch on startup.
          dbus
          fontconfig
          freetype
          udev
          stdenv.cc.cc.lib
        ];
      in
      {
        devShells.default = pkgs.mkShell {
          packages = [ jdk ];

          shellHook = ''
            export JAVA_HOME=${jdk}/lib/openjdk

            # /run/opengl-driver/lib is populated by NixOS `hardware.graphics`
            # and provides the active GPU vendor's GLX / EGL. Keep it first so
            # the vendor ICD wins over the nixpkgs fallback.
            export LD_LIBRARY_PATH=/run/opengl-driver/lib:${pkgs.lib.makeLibraryPath lwjglRuntimeLibs}''${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
          '';
        };
      });
}
