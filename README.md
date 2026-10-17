# Kalasetu
<div align="center">

# 🛍️ **KALASETU**
## AI-Powered Artisan Marketplace with Real-Time Firebase Integration

[![Firebase](https://img.shields.io/badge/Firebase-FFCB2D?style=for-the-badge&logo=firebase&logoColor=white)](https://firebase.google.com/)
[![JavaScript](https://img.shields.io/badge/Vanilla_JS-F7DF1E?style=for-the-badge&logo=javascript&logoColor=black)](https://developer.mozilla.org/en-US/docs/Web/JavaScript)
[![Live Demo](https://img.shields.io/badge/Live_Demo-42b883?style=for-the-badge&logo=github&logoColor=white)](https://kalasetu-25a17.web.app/)
[![License](https://img.shields.io/github/license/YOUR_USERNAME/kalasetu-artisan-marketplace)](LICENSE)

</div>

---

## 🎯 **Project Overview**

**Kalasetu** empowers traditional artisans by connecting them **directly** to global customers, eliminating exploitative middlemen. Built with **Firebase serverless architecture**, it provides **real-time product synchronization** (sub-200ms updates), **AI-powered storytelling**, and **responsive design** across all devices.

### **🌟 Key Features**
- 🔐 **Secure Authentication** - Firebase Auth (email/password)
- 🛒 **Real-Time Marketplace** - Products appear instantly
- 📸 **Product Upload** - Cloud Storage + Firestore metadata
- 🛍️ **Shopping Cart** - localStorage management
- 🤝 **Artisan Networking** - Peer-to-peer connections
- ✍️ **AI Story Generator** - Heritage product narratives
- 📊 **Analytics Dashboard** - Real-time sales metrics
- 📱 **Mobile Responsive** - All screen sizes

---

### 💳 **Order Service & Payments**

Checkout can run through a separate **Spring Boot** service ([`order-service/`](order-service/)) deployed on Cloud Run:

- Server-side pricing and atomic stock reservation in Firestore transactions
- Razorpay payments with HMAC-verified checkout callbacks and webhooks
- Idempotency keys, so a retried request never creates a duplicate order
- Order state machine (`PENDING_PAYMENT → PAID → SHIPPED → DELIVERED`) with automatic expiry of unpaid orders
- Append-only ledger of what each artisan is owed

Design, data model and failure handling: [`docs/DESIGN.md`](docs/DESIGN.md). Until `public/config.js` points at the deployed service, checkout uses the original flow.

---

### 🛠️ **Tech Stack**

**Frontend**: HTML5, CSS3 (Flexbox, Media Queries), Vanilla JavaScript

**Backend**: Firebase (Auth, Firestore, Cloud Storage)

**Order Service**: Java 21, Spring Boot, Razorpay, Docker, Cloud Run

**Database**: Firestore NoSQL (Real-time WebSocket)

**Hosting**: Firebase Hosting (Global CDN)

**Deployment**: Firebase CLI


---

## 🚀 **Live Demo**
🔗 **[https://kalasetu-25a17.web.app/](https://kalasetu-25a17.web.app/)**

**🧪 Test Credentials:**

Email: testartisan@example.com

Password: 123456

---

## 📱 **Screenshots**
| **Marketplace** | **Product Upload** | **Dashboard** |
|---|---|---|
| ![Marketplace](screenshots/marketplace.png) | ![Upload](screenshots/upload.png) | ![Dashboard](screenshots/dashboard.png) |

---

## 🎮 **Architecture Flow**

User Login → Firebase Auth → Firestore Query → Add Product →
Cloud Storage → Real-time Sync → All Devices Updated Instantly

---

## 🚀 **Quick Start**

### **Prerequisites**

Node.js 18+

Firebase Account (Free)

### **Setup (5 minutes)**
```bash
git clone https://github.com/YOUR_USERNAME/kalasetu-artisan-marketplace.git
cd kalasetu-artisan-marketplace
npm install -g firebase-tools
firebase login
firebase serve
```
🔥 Deploy
```bash
firebase init hosting
firebase deploy
```
---
## 📁 **Files**
```bash
├── index.html
├── app.js 
├── style.css 
├── checkout.html
├── checkout.js
└── README.md
```
---

## 📊 **Performance**
| Metric    | Result |
| --------- | ------ |
| Page Load | 1.2–2s |
| Real-time | <200ms |
| Memory    | 80MB   |


---
## 🔒 **Security**

✅ Firebase Auth

✅ Firestore Rules

✅ HTTPS everywhere

✅ Input validation

---

## 🎯 **Why Unique?**

Zero middlemen = 100% artisan profit

AI stories preserve culture

Global CDN = instant access

Serverless = no server cost

---

## 🌟 **Next Steps**

Razorpay payments

Hindi language

Mobile app

Admin panel

---
<div align="center"> ⭐Star if helpful!  Built with ❤️ for artisan empowerment </div>
