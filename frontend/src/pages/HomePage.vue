<script setup lang="ts">
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
</script>

<template>
  <h1 tabindex="-1" class="text-3xl font-bold tracking-tight">{{ t('pages.home.title') }}</h1>
  <p class="mt-4 max-w-prose text-lg text-slate-700 dark:text-slate-300">
    {{ t('pages.home.intro') }}
  </p>
  <p class="mt-4 max-w-prose text-slate-700 dark:text-slate-300">{{ t('pages.home.status') }}</p>
</template>
