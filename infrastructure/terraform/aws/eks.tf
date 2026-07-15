# =============================================================================
# EKS Cluster – Production-grade configuration
# - Private API server endpoint
# - Managed node groups with mixed instance policy
# - IRSA (IAM Roles for Service Accounts) via OIDC provider
# - EKS add-ons: VPC CNI, CoreDNS, kube-proxy, EBS CSI driver
# - Cluster autoscaler-compatible labels/taints
# =============================================================================

# Using the community EKS module for well-tested, best-practice defaults
module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 20.8"

  cluster_name    = local.eks_cluster_name
  cluster_version = var.eks_cluster_version

  # Private-only API server – kubectl access via VPN or bastion only
  cluster_endpoint_public_access  = false
  cluster_endpoint_private_access = true

  # Encrypt etcd secrets with our CMK
  cluster_encryption_config = {
    provider_key_arn = aws_kms_key.app_data.arn
    resources        = ["secrets"]
  }

  # Ship control-plane logs to CloudWatch (encrypted with CMK)
  cluster_enabled_log_types = ["api", "audit", "authenticator", "controllerManager", "scheduler"]

  vpc_id                   = aws_vpc.main.id
  subnet_ids               = aws_subnet.private[*].id
  control_plane_subnet_ids = aws_subnet.private[*].id

  # OIDC provider – required for IRSA
  enable_irsa = true

  # Managed add-ons – keep up to date, let AWS handle patching
  cluster_addons = {
    coredns = {
      most_recent = true
      configuration_values = jsonencode({
        replicaCount = 3
        resources = {
          requests = { cpu = "100m", memory = "70Mi" }
          limits   = { cpu = "200m", memory = "170Mi" }
        }
      })
    }
    kube-proxy = { most_recent = true }
    vpc-cni = {
      most_recent              = true
      before_compute           = true
      service_account_role_arn = module.vpc_cni_irsa.iam_role_arn
      configuration_values = jsonencode({
        env = {
          ENABLE_PREFIX_DELEGATION = "true"  # Higher pod density per node
          WARM_PREFIX_TARGET       = "1"
        }
      })
    }
    aws-ebs-csi-driver = {
      most_recent              = true
      service_account_role_arn = module.ebs_csi_irsa.iam_role_arn
    }
  }

  # Default node security group – restrict to minimum required
  node_security_group_additional_rules = {
    ingress_self_all = {
      description = "Allow all intra-node communication"
      protocol    = "-1"
      from_port   = 0
      to_port     = 0
      type        = "ingress"
      self        = true
    }
  }

  eks_managed_node_groups = {
    # System node group – runs kube-system, monitoring
    system = {
      name           = "system"
      instance_types = ["m6i.large"]
      min_size       = 2
      max_size       = 4
      desired_size   = 2

      labels = { role = "system" }
      taints = [{
        key    = "CriticalAddonsOnly"
        value  = "true"
        effect = "NO_SCHEDULE"
      }]

      block_device_mappings = {
        xvda = {
          device_name = "/dev/xvda"
          ebs = {
            volume_size           = 50
            volume_type           = "gp3"
            encrypted             = true
            kms_key_id            = aws_kms_key.app_data.arn
            delete_on_termination = true
          }
        }
      }
    }

    # Application node group – mixed instance policy for cost optimization
    application = {
      name           = "application"
      instance_types = var.eks_node_instance_types
      min_size       = var.eks_node_group_min_size
      max_size       = var.eks_node_group_max_size
      desired_size   = var.eks_node_group_desired_size

      # Spread across AZs for fault tolerance
      subnet_ids = aws_subnet.private[*].id

      labels = {
        role                       = "application"
        "node.kubernetes.io/type"  = "general"
      }

      # Cluster Autoscaler tags
      tags = {
        "k8s.io/cluster-autoscaler/enabled"                = "true"
        "k8s.io/cluster-autoscaler/${local.eks_cluster_name}" = "owned"
      }

      block_device_mappings = {
        xvda = {
          device_name = "/dev/xvda"
          ebs = {
            volume_size           = 100
            volume_type           = "gp3"
            iops                  = 3000
            throughput            = 125
            encrypted             = true
            kms_key_id            = aws_kms_key.app_data.arn
            delete_on_termination = true
          }
        }
      }
    }

    # Spark node group – compute-optimized, spot instances for batch jobs
    spark = {
      name           = "spark"
      instance_types = ["c6i.4xlarge", "c6a.4xlarge", "c5.4xlarge"]
      capacity_type  = "SPOT"
      min_size       = 0
      max_size       = 20
      desired_size   = 0

      labels = { role = "spark", "node.kubernetes.io/type" = "compute" }
      taints = [{
        key    = "spark-workload"
        value  = "true"
        effect = "NO_SCHEDULE"
      }]

      block_device_mappings = {
        xvda = {
          device_name = "/dev/xvda"
          ebs = {
            volume_size           = 200
            volume_type           = "gp3"
            encrypted             = true
            kms_key_id            = aws_kms_key.app_data.arn
            delete_on_termination = true
          }
        }
      }

      tags = {
        "k8s.io/cluster-autoscaler/enabled"                   = "true"
        "k8s.io/cluster-autoscaler/${local.eks_cluster_name}" = "owned"
        "k8s.io/cluster-autoscaler/node-template/label/role"  = "spark"
      }
    }
  }
}

# IRSA for VPC CNI
module "vpc_cni_irsa" {
  source  = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  version = "~> 5.39"

  role_name_prefix      = "${var.project_name}-vpc-cni"
  attach_vpc_cni_policy = true
  vpc_cni_enable_ipv4   = true

  oidc_providers = {
    main = {
      provider_arn               = module.eks.oidc_provider_arn
      namespace_service_accounts = ["kube-system:aws-node"]
    }
  }
}

# IRSA for EBS CSI Driver
module "ebs_csi_irsa" {
  source  = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  version = "~> 5.39"

  role_name_prefix      = "${var.project_name}-ebs-csi"
  attach_ebs_csi_policy = true

  oidc_providers = {
    main = {
      provider_arn               = module.eks.oidc_provider_arn
      namespace_service_accounts = ["kube-system:ebs-csi-controller-sa"]
    }
  }
}

# StorageClass – encrypted gp3 EBS volumes (default for all PVCs)
resource "kubernetes_storage_class" "gp3_encrypted" {
  metadata {
    name = "gp3-encrypted"
    annotations = {
      "storageclass.kubernetes.io/is-default-class" = "true"
    }
  }

  storage_provisioner    = "ebs.csi.aws.com"
  volume_binding_mode    = "WaitForFirstConsumer"
  reclaim_policy         = "Retain"
  allow_volume_expansion = true

  parameters = {
    type      = "gp3"
    encrypted = "true"
    kmsKeyId  = aws_kms_key.app_data.arn
    fsType    = "ext4"
  }

  depends_on = [module.eks]
}
