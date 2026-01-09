import { useLettaConfigStore } from "@/stores/lettaConfigStore"

// Export the store's functions and client for direct use
export const useLettaClient = () => {
  const { client } = useLettaConfigStore()

  return { lettaClient: client! }
}
