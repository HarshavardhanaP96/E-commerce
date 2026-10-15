# Quick Start Guide - Deployment

## 🚀 5-Minute Setup (Prerequisites)

### Step 1: GitHub Secrets (2 min)
```bash
# Get your kubeconfig
cat ~/.kube/config | base64 -w 0

# Set secrets (use GitHub UI or CLI)
gh secret set DOCKERHUB_USERNAME -b"your_username"
gh secret set DOCKERHUB_TOKEN -b"your_token"
gh secret set KUBE_CONFIG -b"paste_base64_kubeconfig"
```

### Step 2: Kubernetes Setup (3 min)
```bash
# Create namespaces
kubectl create namespace test-env
kubectl create namespace prod-env

# Create secrets for test
kubectl create secret generic backend-secrets \
  --from-literal=MONGO_URI='mongodb+srv://user:pass@cluster.mongodb.net/' \
  --from-literal=RAZORPAY_KEY_ID='rzp_test_xxxxx' \
  --from-literal=RAZORPAY_KEY_SECRET='your_test_secret' \
  -n test-env

# Create secrets for prod
kubectl create secret generic backend-secrets \
  --from-literal=MONGO_URI='mongodb+srv://user:pass@cluster.mongodb.net/' \
  --from-literal=RAZORPAY_KEY_ID='rzp_live_xxxxx' \
  --from-literal=RAZORPAY_KEY_SECRET='your_live_secret' \
  -n prod-env
```

### Step 3: Update Manifests (1 min)
```bash
# Replace DOCKERHUB_USERNAME with your actual username in these files:
# - k8s/test/backend.yaml (line 18)
# - k8s/test/frontend.yaml
# - k8s/prod/backend.yaml (line 18)
# - k8s/prod/frontend.yaml

# Quick replace (update YOUR_USERNAME):
find k8s -name "*.yaml" -exec sed -i 's/DOCKERHUB_USERNAME/YOUR_USERNAME/g' {} +
```

---

## 🧪 Test Locally First

```bash
# Run tests
cd ecommerce-backend
npm test

# Build Docker images
docker build -t test-backend ./ecommerce-backend
docker build -t test-frontend ./ecommerce-frontend

# Test backend locally
cd ecommerce-backend
npm run build
npm run dev
```

---

## 🚢 Deploy

### Test Environment
```bash
git checkout -b test-deploy
git add .
git commit -m "Test deployment"
git push origin test-deploy
```

**Monitor:**
- GitHub: Repository → Actions
- Kubernetes: `kubectl get pods -n test-env -w`

### Production
```bash
git checkout main
git merge test-deploy
git push origin main
```

**Monitor:**
- Kubernetes: `kubectl get pods -n prod-env -w`

---

## 🔍 Verify Deployment

```bash
# Check pods
kubectl get pods -n test-env
kubectl get pods -n prod-env

# Check services
kubectl get svc -n test-env
kubectl get svc -n prod-env

# View logs
kubectl logs -f deployment/backend-deploy -n test-env
kubectl logs -f deployment/frontend-deploy -n test-env

# Port forward to test
kubectl port-forward svc/backend-service 8080:80 -n test-env
kubectl port-forward svc/frontend-service 8081:80 -n test-env
```

---

## ⚡ Quick Commands

### Run Tests
```bash
cd ecommerce-backend && npm test
```

### Check Pipeline Status
```bash
gh run list --limit 5
gh run view  # View latest run
```

### Restart Deployment
```bash
kubectl rollout restart deployment/backend-deploy -n test-env
kubectl rollout restart deployment/frontend-deploy -n test-env
```

### View Recent Logs
```bash
kubectl logs --tail=50 -l app=backend -n test-env
kubectl logs --tail=50 -l app=frontend -n test-env
```

### Delete and Redeploy
```bash
kubectl delete -f k8s/test/ -n test-env
kubectl apply -f k8s/test/ -n test-env
```

---

## 🐛 Troubleshooting

### Tests Failing?
```bash
cd ecommerce-backend
npm test -- --verbose
```

### Docker Build Failing?
```bash
cd ecommerce-backend
docker build -t test .
```

### Pod Not Starting?
```bash
kubectl describe pod POD_NAME -n test-env
kubectl logs POD_NAME -n test-env
```

### Image Pull Error?
- Check Docker Hub credentials
- Verify repositories are public
- Check secret: `kubectl get secret -n test-env`

---

## 📚 Full Documentation

- **Detailed Checklist:** `docs/cicd/DEPLOYMENT_CHECKLIST.md`
- **Status Report:** `docs/cicd/STATUS_REPORT.md`
- **CI/CD Overview:** `docs/cicd/README.md`
