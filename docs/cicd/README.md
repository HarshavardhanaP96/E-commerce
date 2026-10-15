# CI/CD and DevOps Documentation

This document explains the full DevOps cycle implemented for the Ecommerce project.

## 1. Architecture Overview

The CI/CD pipeline is built using **GitHub Actions**. It automates the following steps:
- **Testing**: Every commit triggers backend unit tests.
- **Dockerization**: Successfully tested code is built into Docker images.
- **Registry**: Images are pushed to **Docker Hub**.
- **Deployment**: 
  - Commits to feature branches are deployed to the `test-env` namespace on Kubernetes.
  - Commits/Merges to the `main` branch are deployed to the `prod-env` namespace.

## 2. Prerequisites

To make this pipeline work, you need the following:
1. **GitHub Repository**: Where your code resides.
2. **Docker Hub Account**: To store your container images.
3. **Kubernetes Cluster**: Hosted on EC2 (or any provider).
4. **GitHub Secrets**: You must configure the following secrets in your repository settings:
   - `DOCKERHUB_USERNAME`: Your Docker Hub username.
   - `DOCKERHUB_TOKEN`: Your Docker Hub access token.
   - `KUBE_CONFIG`: The base64 encoded content of your `~/.kube/config` file (or the raw YAML).

## 3. Kubernetes Setup

Before running the pipeline, ensure the namespaces exist in your cluster:

```bash
kubectl create namespace test-env
kubectl create namespace prod-env
```

You also need to create secrets for the backend (MongoDB URI, Stripe Key, etc.) in both namespaces:

```bash
kubectl create secret generic backend-secrets \
  --from-literal=MONGO_URI='your_mongodb_uri' \
  --from-literal=STRIPE_KEY='your_stripe_key' \
  -n test-env

# Repeat for prod-env
```

## 4. ArgoCD (GitOps) - Optional

If you want to use ArgoCD for continuous delivery:
1. Install ArgoCD in your cluster.
2. Connect your GitHub repository to ArgoCD.
3. Create two Applications in ArgoCD:
   - **ecommerce-test**: Points to `k8s/test` directory.
   - **ecommerce-prod**: Points to `k8s/prod` directory.

ArgoCD will automatically sync the state of the cluster with the manifests in these folders.

## 5. Directory Structure

- `.github/workflows/main.yml`: The CI/CD pipeline definition.
- `ecommerce-backend/Dockerfile`: Docker configuration for the API.
- `ecommerce-frontend/Dockerfile`: Docker configuration for the Frontend.
- `k8s/`: Contains all Kubernetes manifests for Test and Prod environments.
- `docs/`: Deployment and DevOps documentation.
