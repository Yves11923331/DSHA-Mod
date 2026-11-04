# DSHA dsha.v3 / build312

- Nouveau greffon de guide `dsh-saf-guide` : l'accès aux fichiers inter-applications entre dans l'invite système (le port est lu uniquement depuis `/root/.dsh/.bridge_port`, jamais supposé à 3090), donc l'assistant trouve ce canal sans installer de compétence ; la section disparaît quand la capacité est indisponible.
- L'accès inter-applications arrive dans la version coexistante : nouvelle ligne dans les autorisations, page de gestion (choisir un dossier / autoriser l'écriture / retirer), navigateur en lecture seule et neuf points d'accès `/app/saf/*`.
- La base reste build310 (`f0f11905`) ; le Dock paysage, l'écran toujours allumé et les icônes d'hiver sont conservés.
- Périmètre de vérification : compilation hors ligne et les sept portes `preBuild` réussies ; l'autorisation, la lecture/écriture et la révocation sur appareil seront vérifiées après installation. versionCode 312, versionName dsha.v3, paquet dsga.mod.
