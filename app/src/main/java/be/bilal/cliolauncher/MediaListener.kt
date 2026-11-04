package be.bilal.cliolauncher

import android.service.notification.NotificationListenerService

// Service vide : il sert uniquement à obtenir l'autorisation de lire
// la musique en cours (titre, artiste, pochette) des autres applis.
class MediaListener : NotificationListenerService()
