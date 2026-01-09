import { storage } from "@/utils/storage"
import type { StateStorage } from "zustand/middleware"

export const zustandStorage: StateStorage = {
  setItem: (name, value) => {
    try {
      storage.set(name, value)
      return Promise.resolve()
    } catch (error) {
      return Promise.reject(error)
    }
  },
  getItem: (name) => {
    try {
      const value = storage.getString(name)
      return Promise.resolve(value ?? null)
    } catch (error) {
      return Promise.reject(error)
    }
  },
  removeItem: (name) => {
    try {
      storage.delete(name)
      return Promise.resolve()
    } catch (error) {
      return Promise.reject(error)
    }
  },
}
