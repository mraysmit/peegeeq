import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http'
import type { AddressInfo } from 'node:net'

export interface RecordedHttpRequest {
  method: string
  url: string
  headers: IncomingMessage['headers']
  body: string
}

export type HttpResponder = (
  request: RecordedHttpRequest,
  response: ServerResponse,
) => void | Promise<void>

const defaultResponder: HttpResponder = (_request, response) => {
  response.setHeader('Content-Type', 'application/json')
  response.end(JSON.stringify({ ok: true }))
}

export class HttpTestServer {
  private server: Server | undefined
  private responder: HttpResponder = defaultResponder

  readonly requests: RecordedHttpRequest[] = []

  setResponder(responder: HttpResponder): void {
    this.responder = responder
  }

  async start(): Promise<string> {
    this.server = createServer(async (request, response) => {
      const chunks: Buffer[] = []
      for await (const chunk of request) {
        chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk))
      }

      const recorded: RecordedHttpRequest = {
        method: request.method ?? 'GET',
        url: request.url ?? '/',
        headers: request.headers,
        body: Buffer.concat(chunks).toString('utf8'),
      }
      this.requests.push(recorded)

      response.setHeader('Access-Control-Allow-Origin', '*')
      try {
        await this.responder(recorded, response)
      } catch (error) {
        console.error('HTTP test responder failed', error)
        response.statusCode = 500
        response.end(JSON.stringify({ message: 'HTTP test responder failed' }))
      }
    })

    await new Promise<void>((resolve, reject) => {
      this.server?.once('error', reject)
      this.server?.listen(0, '127.0.0.1', resolve)
    })

    const address = this.server.address() as AddressInfo
    return `http://127.0.0.1:${address.port}`
  }

  async stop(): Promise<void> {
    const server = this.server
    this.server = undefined
    if (!server) return

    await new Promise<void>((resolve, reject) => {
      server.close((error) => error ? reject(error) : resolve())
    })
  }
}
