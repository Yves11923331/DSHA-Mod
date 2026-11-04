# DSHA dsha.v2 / build311

- Réglages · Autorisations de l'appareil gagne « Fichiers inter-applications » : accordez dossier par dossier via le sélecteur de documents du système, activez ou non l'écriture de l'assistant par dossier, et révoquez à tout moment.
- Nouveaux points d'accès `/app/saf/*` : lister, lire (texte, base64, par plages), écrire un petit texte, récupérer un fichier dans le conteneur, renvoyer un fichier du conteneur, créer un dossier et supprimer avec confirmation. Seuls les identifiants de dossiers autorisés sont acceptés.
- Rebasé sur build310 : identité de publication commutable, correctifs de notifications d'îlot, correctifs de frontière pour les sauvegardes et la source d'installation via le pont ; le Dock paysage, l'écran toujours allumé et les icônes d'hiver sont conservés.
- Périmètre de vérification : compilation hors ligne et contrôles de source locaux réussis ; la vérification sur appareil (autorisation, lecture/écriture, révocation) suit la construction cloud. versionCode 311 (basé sur build310), versionName dsha.v2, paquet dsga.mod.
