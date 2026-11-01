# Shared settings for the scripts in this repo (sourced, not run).

# The apps you use. They are kept by debloat.sh --remove-apps, background-restricted by
# launcher/install.sh, and closed by the RAM cleaner when you go home.
# The home screen's tiles are separate: launcher/src/com/custom/tvhome/AppCatalog.java.
APPS=(
  com.netflix.ninja           # Netflix
  org.smarttube.stable        # SmartTube
  com.amazon.firebat          # Prime Video (Fire OS 8)
  com.stremio.one             # Stremio
  com.amazon.firetv.youtube   # YouTube
)

# Amazon services that debloat.sh disables: ads, tracking, promos and unused Amazon features.
# Each was checked against community debloat reports; anything that breaks the launcher, remote,
# Alexa voice search, Wi-Fi, Appstore or DRM playback is deliberately left out.
# Remove a line to keep that feature.
DISABLE=(
  # ads and tracking
  com.amazon.tv.acr com.amazon.hybridadidservice com.amazon.privacypassservice
  com.amazon.kso.blackbird com.amazon.sneakpeek com.amazon.media.recommendations
  com.amazon.systemnotices com.amazon.uxnotification com.amazon.tv.website_launcher
  # telemetry and logging
  com.amazon.wirelessmetrics.service com.amazon.perfcollection com.amazon.logan
  com.amazon.dp.logger com.amazon.ceviche com.amazon.hedwig com.amazon.tigris com.amazon.ale
  com.amazon.tv.developer.dataservice
  # Amazon features: Ambient Experience, shopping, miniTV, games, Music, Photos
  com.amazon.tv.ftvambient com.amazon.shoptv.client com.amazon.shoptv.firetv.client
  com.amazon.minitv.android.app com.amazon.gamehub com.amazon.bueller.music com.amazon.bueller.photos
  # smart home, Spotify via Alexa, Amazon Kids, Silk Browser
  com.amazon.smarthomemapviewapp com.amazon.tv.matter com.amazon.spotify.mediabrowserservice
  com.amazon.tahoe com.amazon.cloud9
  # screen mirroring and the web-casting receiver (casting from the YouTube/Netflix phone apps still works)
  com.amazon.cast.sink com.amazon.awvflingreceiver
  # help videos, tutorials and nags
  com.amazon.tv.support com.amazon.storm.lightning.tutorial com.amazon.tmm.tutorial
  com.amazon.tv.releasenotes com.amazon.tv.easyupgrade
  com.android.protips com.android.printservice.recommendation
)

# Fire OS update services: suspended (they are protected from disabling) unless --keep-updates.
UPDATE_PKGS=(com.amazon.device.software.ota com.amazon.device.software.ota.override)
UPDATE_NAG=com.amazon.tv.forcedotaupdater.v2

# Normalises "192.168.1.50" to "192.168.1.50:5555".
tv_addr() { [[ $1 == *:* ]] && echo "$1" || echo "$1:5555"; }
