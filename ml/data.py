"""CIFAR-10 data: normalization constants, augmentation and loaders.

The 10,000-image test set is used as the validation set.
"""

import torch
import torchvision
import torchvision.transforms as T

# Per-channel mean and std of the CIFAR-10 training images.
# The offline tool uses the same values to normalize and quantize the input.
MEAN = (0.4914, 0.4822, 0.4465)
STD = (0.2470, 0.2435, 0.2616)


def train_transform():
    # Augmentation is used for training only.
    return T.Compose([
        T.RandomCrop(32, padding=4),
        T.RandomHorizontalFlip(),
        T.ToTensor(),
        T.Normalize(MEAN, STD),
    ])


def test_transform():
    return T.Compose([T.ToTensor(), T.Normalize(MEAN, STD)])


def get_datasets(root, download=True):
    """Return (train_aug, train_plain, test). train_plain has no augmentation."""
    train_aug = torchvision.datasets.CIFAR10(root, train=True, download=download,
                                             transform=train_transform())
    train_plain = torchvision.datasets.CIFAR10(root, train=True, download=False,
                                               transform=test_transform())
    test = torchvision.datasets.CIFAR10(root, train=False, download=download,
                                        transform=test_transform())
    return train_aug, train_plain, test


def get_loaders(root, batch_size, workers, generator, pin_memory=True, eval_batch_size=512):
    """Return (train_loader, train_eval_loader, test_loader).

    `generator` drives the shuffle order and the worker seeds, so saving its
    state in the checkpoint makes --resume continue the same data order.
    Workers are not persistent, so each epoch gets new worker seeds from
    the generator.
    """
    train_aug, train_plain, test = get_datasets(root)
    common = dict(num_workers=workers, pin_memory=pin_memory)
    train_loader = torch.utils.data.DataLoader(
        train_aug, batch_size=batch_size, shuffle=True, drop_last=False,
        generator=generator, **common)
    train_eval_loader = torch.utils.data.DataLoader(
        train_plain, batch_size=eval_batch_size, shuffle=False, **common)
    test_loader = torch.utils.data.DataLoader(
        test, batch_size=eval_batch_size, shuffle=False, **common)
    return train_loader, train_eval_loader, test_loader
