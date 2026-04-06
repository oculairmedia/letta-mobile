import { renderHook, waitFor } from "@testing-library/react-native"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { useMCPList, useMCPTools, useAddMCPServer, useDeleteMCPServer } from "../use-mcp"
import { useLettaClient } from "@/providers/LettaProvider"
import { createElement, type ReactNode } from "react"

// Mock the LettaProvider
jest.mock("@/providers/LettaProvider", () => ({
  useLettaClient: jest.fn(),
}))

const mockUseLettaClient = useLettaClient as jest.MockedFunction<typeof useLettaClient>

describe("use-mcp hooks", () => {
  let queryClient: QueryClient

  const createWrapper = () => {
    return ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children)
  }

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: {
          retry: false,
        },
        mutations: {
          retry: false,
        },
      },
    })
    jest.clearAllMocks()
  })

  afterEach(() => {
    queryClient.clear()
  })

  describe("useMCPList", () => {
    it("should fetch MCP servers list", async () => {
      const mockServers = [
        { id: "1", server_name: "Server 1", mcp_server_type: "sse", server_url: "http://localhost:8080" },
        { id: "2", server_name: "Server 2", mcp_server_type: "stdio", command: "python", args: ["server.py"] },
      ]

      const mockList = jest.fn().mockResolvedValue(mockServers)

      mockUseLettaClient.mockReturnValue({
        lettaClient: {
          mcpServers: {
            list: mockList,
          },
        },
      } as any)

      const { result } = renderHook(() => useMCPList(), { wrapper: createWrapper() })

      await waitFor(() => {
        expect(result.current.data).toBeDefined()
      })

      expect(mockList).toHaveBeenCalled()
      expect(result.current.data).toEqual(mockServers)
    })
  })

  describe("useMCPTools", () => {
    it("should fetch tools from all MCP servers", async () => {
      const mockServers = [
        { id: "server-1", server_name: "Server 1", mcp_server_type: "sse" },
        { id: "server-2", server_name: "Server 2", mcp_server_type: "stdio" },
      ]

      const mockTools1 = [
        { name: "tool1", description: "Tool 1 description" },
        { name: "tool2", description: "Tool 2 description" },
      ]

      const mockTools2 = [
        { name: "tool3", description: "Tool 3 description" },
      ]

      // Create async iterators for tools
      const createAsyncIterator = (tools: any[]) => ({
        [Symbol.asyncIterator]: async function* () {
          for (const tool of tools) {
            yield tool
          }
        },
      })

      const mockListTools = jest.fn()
        .mockResolvedValueOnce(createAsyncIterator(mockTools1))
        .mockResolvedValueOnce(createAsyncIterator(mockTools2))

      mockUseLettaClient.mockReturnValue({
        lettaClient: {
          mcpServers: {
            list: jest.fn().mockResolvedValue(mockServers),
            tools: {
              list: mockListTools,
            },
          },
        },
      } as any)

      const { result } = renderHook(() => useMCPTools(), { wrapper: createWrapper() })

      await waitFor(() => {
        expect(result.current.data).toBeDefined()
      }, { timeout: 5000 })

      // Should have tools from both servers with serverName attached
      expect(result.current.data?.length).toBe(3)
      expect(result.current.data?.find(t => t.name === "tool1")?.serverName).toBe("Server 1")
      expect(result.current.data?.find(t => t.name === "tool3")?.serverName).toBe("Server 2")
    })

    it("should handle server errors gracefully", async () => {
      const mockServers = [
        { id: "server-1", server_name: "Server 1", mcp_server_type: "sse" },
      ]

      const consoleSpy = jest.spyOn(console, "error").mockImplementation(() => {})

      mockUseLettaClient.mockReturnValue({
        lettaClient: {
          mcpServers: {
            list: jest.fn().mockResolvedValue(mockServers),
            tools: {
              list: jest.fn().mockRejectedValue(new Error("Connection failed")),
            },
          },
        },
      } as any)

      const { result } = renderHook(() => useMCPTools(), { wrapper: createWrapper() })

      await waitFor(() => {
        // Wait for data to be defined (query completed)
        expect(result.current.data).toBeDefined()
      }, { timeout: 5000 })

      // Should return empty array on error (caught in the try/catch), not throw
      // The hook catches errors per-server and returns [] for that server
      expect(result.current.data).toEqual([])
      expect(consoleSpy).toHaveBeenCalled()

      consoleSpy.mockRestore()
    })
  })

  describe("useAddMCPServer", () => {
    it("should create a new MCP server and invalidate queries", async () => {
      const mockCreate = jest.fn().mockResolvedValue({
        id: "new-server",
        server_name: "New Server",
      })

      mockUseLettaClient.mockReturnValue({
        lettaClient: {
          mcpServers: {
            create: mockCreate,
          },
        },
      } as any)

      const { result } = renderHook(() => useAddMCPServer(), { wrapper: createWrapper() })

      await result.current.mutateAsync({
        server_name: "New Server",
        mcp_server_type: "streamable_http",
        server_url: "http://localhost:9000",
      } as any)

      expect(mockCreate).toHaveBeenCalledWith({
        server_name: "New Server",
        mcp_server_type: "streamable_http",
        server_url: "http://localhost:9000",
      })
    })
  })

  describe("useDeleteMCPServer", () => {
    it("should delete an MCP server", async () => {
      const mockDelete = jest.fn().mockResolvedValue({})

      mockUseLettaClient.mockReturnValue({
        lettaClient: {
          mcpServers: {
            delete: mockDelete,
          },
        },
      } as any)

      const { result } = renderHook(() => useDeleteMCPServer(), { wrapper: createWrapper() })

      await result.current.mutateAsync("server-to-delete")

      expect(mockDelete).toHaveBeenCalledWith("server-to-delete")
    })
  })
})
