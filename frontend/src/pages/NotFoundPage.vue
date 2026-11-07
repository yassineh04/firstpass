<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'

const { t } = useI18n()
</script>

<template>
  <h1 tabindex="-1" class="text-3xl font-bold tracking-tight">{{ t('pages.notFound.title') }}</h1>
  <p class="mt-4 max-w-prose text-slate-700 dark:text-slate-300">{{ t('pages.notFound.body') }}</p>
  <p class="mt-6">
    <RouterLink
      to="/"
      class="font-medium text-blue-800 underline underline-offset-4 dark:text-blue-300"
    >
      {{ t('pages.notFound.backHome') }}
    </RouterLink>
  </p>
</template>
