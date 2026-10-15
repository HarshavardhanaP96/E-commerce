# Deployment Checklist

This checklist ensures all prerequisites are met before the CI/CD pipeline can successfully deploy your e-commerce application.

## ✅ Completed Items

### Testing
- [x] Test files created (`app.test.ts`, `features.test.ts`)
- [x] Jest configuration properly set up
- [x] **All tests passing** (4 tests, 2 test suites)
- [x] TypeScript configuration fixed for ESM support

### CI/CD Pipeline
- [x] GitHub Actions workflow created (`.github/workflows/main.yml`)
- [x] Dockerfiles created for backend and frontend
- [x] Kubernetes manifests created for test and prod environments
- [x] nginx configuration for frontend

### Code Quality
- [x] TypeScript compilation working
- [x] ESM modules properly configured
- [x] Test isolation (no database dependencies in unit tests)

---

## ⚠️ Pending Verification

### 1. GitHub Repository Setup

#### GitHub Secrets Configuration
You need to add the following secrets to your GitHub repository:

1. **Navigate to**: Repository → Settings → Secrets and variables → Actions
2. **Add these secrets**:

| Secret Name | Description | How to Get |
|------------|-------------|------------|
| `DOCKERHUB_USERNAME` | Your Docker Hub username | Your Docker Hub account username |
| `DOCKERHUB_TOKEN` | Docker Hub access token | Docker Hub → Account Settings → Security → New Access Token |
| `KUBE_CONFIG` | Kubernetes config file | Run: `cat ~/.kube/config \| base64 -w 0` |

**Verification Command:**
```bash
# Check if secrets are set (you won't see values, just names)
gh secret list
```

---

### 2. Docker Hub Setup

#### Create Docker Hub Repository
1. Log in to [Docker Hub](https://hub.docker.com/)
2. Create two repositories:
   - `ecommerce-backend`
   - `ecommerce-frontend`
3. Set visibility to **Public** (or Private if you have a paid plan)

**Verification:**
```bash
# Test Docker Hub login
docker login -u YOUR_DOCKERHUB_USERNAME

# Verify you can push (optional)
docker tag hello-world YOUR_DOCKERHUB_USERNAME/test:latest
docker push YOUR_DOCKERHUB_USERNAME/test:latest
```

---

### 3. Kubernetes Cluster Setup

#### Create Namespaces
```bash
# Create test environment namespace
kubectl create namespace test-env

# Create production environment namespace
kubectl create namespace prod-env

# Verify namespaces
kubectl get namespaces
```

#### Create Kubernetes Secrets

**For Test Environment:**
```bash
kubectl create secret generic backend-secrets \
  --from-literal=MONGO_URI='your_mongodb_uri_here' \
  --from-literal=RAZORPAY_KEY_ID='your_test_key_id' \
  --from-literal=RAZORPAY_KEY_SECRET='your_test_key_secret' \
  -n test-env

# Verify secret
kubectl get secrets -n test-env
```

**For Production Environment:**
```bash
kubectl create secret generic backend-secrets \
  --from-literal=MONGO_URI='your_production_mongodb_uri' \
  --from-literal=RAZORPAY_KEY_ID='your_live_key_id' \
  --from-literal=RAZORPAY_KEY_SECRET='your_live_key_secret' \
  -n prod-env

# Verify secret
kubectl get secrets -n prod-env
```

#### Update Kubernetes Manifests
Before deploying, update the image names in your Kubernetes manifests:

**Files to update:**
- `k8s/test/backend.yaml` (line 18)
- `k8s/test/frontend.yaml`
- `k8s/prod/backend.yaml` (line 18)
- `k8s/prod/frontend.yaml`

Replace `DOCKERHUB_USERNAME` with your actual Docker Hub username.

**Verification:**
```bash
# Check cluster connectivity
kubectl cluster-info

# Check if you can create resources
kubectl auth can-i create deployments --namespace=test-env
kubectl auth can-i create deployments --namespace=prod-env
```

---

### 4. Environment Variables

#### Backend Environment Variables Required
Ensure your `.env` file has all required variables:

```env
MONGO_URI=mongodb+srv://username:password@cluster.mongodb.net/
PORT=3000
PRODUCT_PER_PAGE=10
RAZORPAY_KEY_ID=rzp_test_xxxxxxxxxxxxx
RAZORPAY_KEY_SECRET=your_secret_here
JWT_SECRET=your_super_secret_jwt_key_here
CLIENT_URL=http://localhost:5173
```

#### Production Considerations
- Use **production MongoDB URI** (not development)
- Use **production Razorpay keys** (not test keys)
- Update `CLIENT_URL` in `k8s/prod/backend.yaml` to your actual domain

---

### 5. MongoDB Setup

#### Database Preparation
- [ ] MongoDB cluster created (Atlas or self-hosted)
- [ ] Database user created with read/write permissions
- [ ] IP whitelist configured (or 0.0.0.0/0 for testing)
- [ ] Connection string tested locally

**Test Connection:**
```bash
# From backend directory
cd ecommerce-backend
npm run build
node dist/app.js
# Should see: "DB connected to cluster0.xxxxx.mongodb.net"
```

---

### 6. Razorpay Setup

#### Payment Configuration
- [ ] Razorpay account created
- [ ] API keys obtained (Key ID and Key Secret for test and production)
- [ ] Test payment flow verified locally

**Test Razorpay Integration:**
```bash
# Use Razorpay test card: 4111 1111 1111 1111
# Any future expiry date, any CVV
```

---

## 🚀 Deployment Process

### First Deployment (Manual Verification)

1. **Push to a feature branch** (not main):
   ```bash
   git checkout -b test-deployment
   git push origin test-deployment
   ```

2. **Monitor GitHub Actions**:
   - Go to: Repository → Actions
   - Watch the workflow run
   - Check each job: `test-backend`, `build-and-push`, `deploy`

3. **Verify Test Environment**:
   ```bash
   # Check deployments
   kubectl get deployments -n test-env
   
   # Check pods
   kubectl get pods -n test-env
   
   # Check services
   kubectl get services -n test-env
   
   # View logs
   kubectl logs -f deployment/backend-deploy -n test-env
   kubectl logs -f deployment/frontend-deploy -n test-env
   ```

4. **Test the Application**:
   ```bash
   # Port forward to access locally
   kubectl port-forward service/backend-service 8080:80 -n test-env
   kubectl port-forward service/frontend-service 8081:80 -n test-env
   
   # Access:
   # Backend: http://localhost:8080
   # Frontend: http://localhost:8081
   ```

### Production Deployment

1. **Merge to main branch**:
   ```bash
   git checkout main
   git merge test-deployment
   git push origin main
   ```

2. **Monitor deployment to prod-env**:
   ```bash
   kubectl get pods -n prod-env -w
   ```

---

## 🔍 Troubleshooting

### Common Issues

#### Tests Failing in CI
```bash
# Run tests locally first
cd ecommerce-backend
npm test

# Check TypeScript compilation
npm run build
```

#### Docker Build Failing
```bash
# Test Docker build locally
cd ecommerce-backend
docker build -t test-backend .

cd ../ecommerce-frontend
docker build -t test-frontend .
```

#### Kubernetes Deployment Failing
```bash
# Check pod status
kubectl describe pod POD_NAME -n test-env

# Check events
kubectl get events -n test-env --sort-by='.lastTimestamp'

# Check logs
kubectl logs POD_NAME -n test-env
```

#### Image Pull Errors
- Verify Docker Hub credentials
- Check if repositories are public
- Verify `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN` secrets

---

## 📊 Current Status

### ✅ Ready for Deployment
- [x] Tests passing (Exit code: 0)
- [x] TypeScript compiling successfully
- [x] Docker configurations ready
- [x] Kubernetes manifests ready
- [x] CI/CD pipeline configured

### ⏳ Awaiting Configuration
- [ ] GitHub Secrets configured
- [ ] Docker Hub repositories created
- [ ] Kubernetes namespaces created
- [ ] Kubernetes secrets created
- [ ] MongoDB production database ready
- [ ] Stripe production keys configured
- [ ] Domain/DNS configured (for production)

---

## 🎯 Next Steps

1. **Configure GitHub Secrets** (5 minutes)
2. **Set up Docker Hub** (5 minutes)
3. **Prepare Kubernetes Cluster** (15 minutes)
4. **Test deployment to test-env** (30 minutes)
5. **Verify application functionality** (15 minutes)
6. **Deploy to production** (when ready)

---

## 📞 Support

If you encounter issues:
1. Check the troubleshooting section above
2. Review GitHub Actions logs
3. Check Kubernetes pod logs
4. Verify all secrets and configurations

**Test Command Summary:**
```bash
# Quick verification
npm test                          # Run tests
docker build -t test .           # Test Docker build
kubectl get all -n test-env      # Check Kubernetes resources
kubectl get secrets -n test-env  # Verify secrets exist
```
