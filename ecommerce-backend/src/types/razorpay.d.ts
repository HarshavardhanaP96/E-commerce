declare module 'razorpay' {
  interface RazorpayOptions {
    key_id: string;
    key_secret: string;
  }

  interface OrderOptions {
    amount: number;
    currency: string;
    receipt?: string;
    payment_capture?: number;
    notes?: Record<string, string>;
  }

  interface RazorpayOrder {
    id: string;
    entity: string;
    amount: number;
    amount_paid: number;
    amount_due: number;
    currency: string;
    receipt: string;
    status: string;
    attempts: number;
    notes: any;
    created_at: number;
  }

  class Razorpay {
    constructor(options: RazorpayOptions);
    orders: {
      create(options: OrderOptions): Promise<RazorpayOrder>;
    };
  }

  export = Razorpay;
}
