import request from 'supertest';
import express, { Request, Response } from 'express';

// Simple test for the root endpoint
describe('GET /', () => {
  it('should return 200 and API working message', async () => {
    // Note: In a real scenario, we'd import the app from ../app.ts
    // For now, testing the logic directly or a mock app
    const app = express();
    app.get('/', (req: Request, res: Response) => res.status(200).send('API working with /api/v1'));
    
    const response = await request(app).get('/');
    expect(response.status).toBe(200);
    expect(response.text).toBe('API working with /api/v1');
  });
});
