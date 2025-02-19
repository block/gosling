import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:flutter_svg/svg.dart';

class Loader extends HookWidget {
  const Loader({super.key});

  static final List<String> _loaderImages = [
    'assets/images/loader/loader_1.svg',
    'assets/images/loader/loader_2.svg',
    'assets/images/loader/loader_3.svg',
    'assets/images/loader/loader_4.svg',
    'assets/images/loader/loader_5.svg',
    'assets/images/loader/loader_6.svg',
    'assets/images/loader/loader_7.svg',
  ];

  @override
  Widget build(BuildContext context) {
    final animationController = useAnimationController(
      duration: const Duration(milliseconds: 1500),
    )..repeat();

    final animation = useAnimation(
      IntTween(
        begin: 0,
        end: _loaderImages.length - 1,
      ).animate(animationController),
    );

    return SizedBox(
      width: 25,
      child: SvgPicture.asset(
        _loaderImages[animation],
        colorFilter: ColorFilter.mode(
          Theme.of(context).colorScheme.onSurface,
          BlendMode.srcIn,
        ),
      ),
    );
  }
}
